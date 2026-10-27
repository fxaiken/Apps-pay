import Anthropic from "@anthropic-ai/sdk";
import { z } from "zod";
import {
  type Conversation,
  type Memory,
  type ModelId,
  type Persona,
  type Settings,
  loadMemories,
  newId,
  saveMemories,
} from "./store";

type BetaMessage = Anthropic.Beta.BetaMessage;
type BetaContentBlock = Anthropic.Beta.BetaContentBlock;
type BetaToolUnion = Anthropic.Beta.BetaToolUnion;

const MAX_PAUSE_CONTINUATIONS = 5;
const MAX_TOOL_ROUNDS = 8;

const MemorySaveInput = z.object({ fact: z.string().min(1).max(500) });
const MemoryDeleteInput = z.object({ id: z.string().min(1) });

export function buildSystemPrompt(persona: Persona, memoryEnabled: boolean, memories: Memory[]): string {
  const parts = [
    persona.prompt,
    "Responde na língua do utilizador (por defeito, português). Usa Markdown quando ajudar a leitura.",
    `Data de hoje: ${new Date().toISOString().slice(0, 10)}.`,
  ];
  if (memoryEnabled) {
    parts.push(
      [
        "Tens memória persistente entre conversas.",
        "Usa memory_save quando o utilizador partilhar algo duradouro e útil sobre si (nome, preferências, projetos, contexto de trabalho) ou pedir explicitamente para te lembrares de algo.",
        "Não guardes dados sensíveis (palavras-passe, dados bancários, saúde) nem coisas passageiras.",
        "Usa memory_delete quando o utilizador pedir para esqueceres algo.",
        memories.length
          ? "Memórias atuais (id: facto):\n" + memories.map((m) => `- ${m.id}: ${m.fact}`).join("\n")
          : "Ainda não tens memórias guardadas.",
      ].join("\n"),
    );
  }
  return parts.join("\n\n");
}

function buildTools(conv: Conversation): BetaToolUnion[] {
  const tools: BetaToolUnion[] = [];
  if (conv.webSearch) {
    // Haiku runs the basic web search variant; Opus/Sonnet 5.5 get dynamic filtering.
    tools.push(
      conv.model === "claude-haiku-5-5"
        ? { type: "web_search_20250305", name: "web_search", max_uses: 5 }
        : { type: "web_search_20260209", name: "web_search", max_uses: 5 },
    );
  }
  if (conv.memoryEnabled) {
    tools.push(
      {
        name: "memory_save",
        description: "Guarda um facto duradouro sobre o utilizador para lembrar em conversas futuras.",
        input_schema: {
          type: "object",
          properties: { fact: { type: "string", description: "O facto, numa frase curta." } },
          required: ["fact"],
          additionalProperties: false,
        },
        strict: true,
        eager_input_streaming: true,
      },
      {
        name: "memory_delete",
        description: "Apaga uma memória guardada, pelo seu id.",
        input_schema: {
          type: "object",
          properties: { id: { type: "string", description: "O id da memória a apagar." } },
          required: ["id"],
          additionalProperties: false,
        },
        strict: true,
        eager_input_streaming: true,
      },
    );
  }
  return tools;
}

function supportsServerFallback(model: ModelId): boolean {
  return model !== "claude-haiku-5-5";
}

// After a mid-output fallback, blocks of the declined attempt that precede the last
// fallback marker must not be echoed back (text blocks and paired server-tool blocks may).
function sanitizeForHistory(content: BetaContentBlock[]): BetaContentBlock[] {
  const lastFallback = content.map((b) => b.type).lastIndexOf("fallback");
  if (lastFallback < 0) return content;
  const resultIds = new Set(
    content.flatMap((b) => ("tool_use_id" in b && typeof b.tool_use_id === "string" ? [b.tool_use_id] : [])),
  );
  return content.filter((b, i) => {
    if (i >= lastFallback) return true;
    if (b.type === "thinking" || b.type === "redacted_thinking" || b.type === "tool_use") return false;
    if (b.type === "server_tool_use") return resultIds.has(b.id);
    return true;
  });
}

function runMemoryTool(block: Anthropic.Beta.BetaToolUseBlock): Anthropic.Beta.BetaToolResultBlockParam {
  const memories = loadMemories();
  if (block.name === "memory_save") {
    const parsed = MemorySaveInput.safeParse(block.input);
    if (!parsed.success) {
      return { type: "tool_result", tool_use_id: block.id, is_error: true, content: "INVALID_JSON: o campo 'fact' é obrigatório." };
    }
    const memory: Memory = { id: newId().slice(0, 8), fact: parsed.data.fact.trim(), createdAt: Date.now() };
    saveMemories([...memories, memory]);
    return { type: "tool_result", tool_use_id: block.id, content: `Guardado com id ${memory.id}.` };
  }
  if (block.name === "memory_delete") {
    const parsed = MemoryDeleteInput.safeParse(block.input);
    if (!parsed.success) {
      return { type: "tool_result", tool_use_id: block.id, is_error: true, content: "INVALID_JSON: o campo 'id' é obrigatório." };
    }
    const remaining = memories.filter((m) => m.id !== parsed.data.id);
    if (remaining.length === memories.length) {
      return { type: "tool_result", tool_use_id: block.id, is_error: true, content: "Não existe nenhuma memória com esse id." };
    }
    saveMemories(remaining);
    return { type: "tool_result", tool_use_id: block.id, content: "Memória apagada." };
  }
  return { type: "tool_result", tool_use_id: block.id, is_error: true, content: `Ferramenta desconhecida: ${block.name}` };
}

export type TurnOutcome =
  | { kind: "done" }
  | { kind: "refusal"; explanation: string | null }
  | { kind: "max_tokens" }
  | { kind: "aborted" };

export interface TurnCallbacks {
  /** Called with the in-progress assistant message while it streams. */
  onSnapshot: (message: BetaMessage) => void;
  /** Called whenever conv.messages grew and should be persisted and re-rendered. */
  onHistoryChanged: () => Promise<void> | void;
}

export function friendlyError(err: unknown): string {
  if (err instanceof Anthropic.AuthenticationError) return "Chave de API inválida. Verifique-a nas definições.";
  if (err instanceof Anthropic.PermissionDeniedError) return "A sua chave não tem acesso a este modelo ou funcionalidade.";
  if (err instanceof Anthropic.RateLimitError) return "Limite de pedidos atingido. Espere um pouco e tente outra vez.";
  if (err instanceof Anthropic.BadRequestError) return `Pedido inválido: ${err.message}`;
  if (err instanceof Anthropic.InternalServerError) return "O serviço da Anthropic está com problemas. Tente daqui a pouco.";
  if (err instanceof Anthropic.APIConnectionError) return "Sem ligação à internet ou à API.";
  if (err instanceof Anthropic.APIError) return `Erro da API (${err.status}): ${err.message}`;
  return err instanceof Error ? err.message : String(err);
}

/**
 * Runs one user turn: streams Claude's answer, resumes paused server-tool turns,
 * and executes memory tool calls until Claude finishes. Appends to conv.messages only.
 */
export async function runTurn(
  conv: Conversation,
  settings: Settings,
  callbacks: TurnCallbacks,
  signal: AbortSignal,
): Promise<TurnOutcome> {
  const client = new Anthropic({ apiKey: settings.apiKey, dangerouslyAllowBrowser: true });
  const tools = buildTools(conv);
  const fallback = supportsServerFallback(conv.model);
  let pauses = 0;
  let toolRounds = 0;
  let jsonRetries = 0;

  while (true) {
    const stream = client.beta.messages.stream(
      {
        model: conv.model,
        max_tokens: 64000,
        system: conv.system,
        tools,
        messages: conv.messages,
        cache_control: { type: "ephemeral" },
        thinking: {
          type: "adaptive",
          display: "summarized",
          block_binding: { prefix_mismatch_behavior: "drop_block" },
        },
        output_config: { effort: settings.effort },
        ...(fallback ? { fallbacks: "default" as const } : {}),
        betas: [
          "thinking-binding-controls-2026-08-01",
          ...(fallback ? (["server-side-fallback-2026-07-01"] as const) : []),
        ],
      },
      { signal },
    );
    stream.on("streamEvent", (_event, snapshot) => callbacks.onSnapshot(snapshot));

    let message: BetaMessage;
    try {
      message = await stream.finalMessage();
      jsonRetries = 0;
    } catch (err) {
      if (signal.aborted || err instanceof Anthropic.APIUserAbortError) return { kind: "aborted" };
      if (err instanceof Anthropic.APIError || jsonRetries++ >= 2) throw err;
      // A tool input that could not be parsed at all: re-issue the same turn.
      continue;
    }

    if (message.stop_reason === "refusal") {
      return { kind: "refusal", explanation: message.stop_details?.explanation ?? null };
    }

    const hasToolUse = message.content.some((b) => b.type === "tool_use");
    if (message.stop_reason === "max_tokens" && hasToolUse) return { kind: "max_tokens" };

    conv.messages.push({ role: "assistant", content: sanitizeForHistory(message.content) });
    await callbacks.onHistoryChanged();

    if (message.stop_reason === "pause_turn") {
      if (++pauses > MAX_PAUSE_CONTINUATIONS) return { kind: "done" };
      continue;
    }

    if (message.stop_reason === "max_tokens") return { kind: "max_tokens" };

    const toolUses = message.content.filter((b): b is Anthropic.Beta.BetaToolUseBlock => b.type === "tool_use");
    if (message.stop_reason !== "tool_use" || toolUses.length === 0) return { kind: "done" };

    // Every tool_use must be answered before the history is valid again, even when we stop here.
    conv.messages.push({ role: "user", content: toolUses.map(runMemoryTool) });
    await callbacks.onHistoryChanged();
    if (++toolRounds >= MAX_TOOL_ROUNDS) return { kind: "done" };
  }
}
