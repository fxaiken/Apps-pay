# Aura

Assistente pessoal para web e Android, a funcionar com o modelo **Claude** da Anthropic
(Opus 5.5 por defeito; Sonnet 5.5 e Haiku 5.5 nas definições), com:

- **Pesquisa na internet** com fontes citadas
- **Ficheiros e imagens**: fotos, PDFs e ficheiros de texto/código
- **Personas**: assistentes com instruções próprias (incluídas: Assistente, Programador, Professor, Tradutor)
- **Memória entre conversas**: o Claude guarda factos sobre si (pode ver e apagar em 🧠 Memória)
- **Modo Turbo ⚡**: respostas até 2,5× mais rápidas com o Opus (custa o dobro)
- **Leitura de links**: cole um URL e o Claude lê a página
- **Gerar outra resposta**, **ler em voz alta** e **ditado por voz** (quando o browser suporta)
- **Pesquisa nas conversas**, **partilhar/exportar** em Markdown e **custo estimado** por conversa
- Design inspirado na Siri do iOS 27: tema escuro com gradientes rosa/roxo/azul/laranja, esfera animada,
  "ilha" de estado no topo, brilho à volta do ecrã enquanto responde, painéis translúcidos e animações com as curvas do iOS

## Chave de API

1. Crie uma conta em [console.anthropic.com](https://console.anthropic.com) e adicione crédito.
2. Em **Settings → API Keys**, crie uma chave (`sk-ant-…`).
3. Na app, abra **⚙️ Definições** e cole a chave.

A chave fica guardada só no seu dispositivo e é enviada apenas para a API da Anthropic.
Não partilhe a chave; quem a tiver pode gastar o seu crédito.

## Desenvolvimento

```bash
npm install
npm run dev      # servidor local
npm run build    # gera dist/
```

## Site

Cada push para `claude-chat` publica a versão web no ramo `gh-pages`. Para a pôr online (uma vez):
**Settings → Pages → Build and deployment → Source: Deploy from a branch → `gh-pages` / `(root)` → Save**.
O site fica em `https://fxaiken.github.io/Apps-pay/`.

## Android

O GitHub Actions (`.github/workflows/claude-chat.yml`) compila o APK com o Capacitor em cada push
para o ramo `claude-chat` e publica-o nas **Releases** do repositório.

Para compilar localmente (precisa do Android Studio):

```bash
npm run build
npx cap add android
npx cap sync android
cd android && ./gradlew assembleDebug
```

### Assinatura

O Android só instala uma atualização assinada com a mesma chave da versão instalada.
O workflow usa, por esta ordem:

1. o segredo `AURA_KEYSTORE_BASE64` (keystore em base64, alias `androiddebugkey`, palavra-passe `android`), se existir;
2. caso contrário, uma chave gerada uma vez e guardada na cache do GitHub Actions
   (a cache expira se não houver compilações durante 7 dias; nesse caso a chave muda).

A chave nunca é guardada no repositório.
