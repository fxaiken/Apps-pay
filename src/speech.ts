// Browser speech APIs: dictation (SpeechRecognition) and read-aloud (speechSynthesis).
// Both are feature-detected; Android WebView has no SpeechRecognition, so the mic stays hidden there.

interface RecognitionResultEvent {
  resultIndex: number;
  results: ArrayLike<{ isFinal: boolean; 0: { transcript: string } }>;
}

interface Recognition {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult: ((e: RecognitionResultEvent) => void) | null;
  onend: (() => void) | null;
  onerror: (() => void) | null;
  start(): void;
  stop(): void;
}

type RecognitionCtor = new () => Recognition;

function recognitionCtor(): RecognitionCtor | undefined {
  const w = window as unknown as { SpeechRecognition?: RecognitionCtor; webkitSpeechRecognition?: RecognitionCtor };
  return w.SpeechRecognition ?? w.webkitSpeechRecognition;
}

export interface Dictation {
  stop(): void;
}

export function canDictate(): boolean {
  return !!recognitionCtor();
}

export function dictate(onText: (text: string) => void, onEnd: () => void): Dictation {
  const Ctor = recognitionCtor()!;
  const rec = new Ctor();
  rec.lang = navigator.language || "pt-PT";
  rec.continuous = true;
  rec.interimResults = true;
  let finalText = "";
  rec.onresult = (e) => {
    let interim = "";
    for (let i = e.resultIndex; i < e.results.length; i++) {
      const r = e.results[i];
      if (r.isFinal) finalText += r[0].transcript;
      else interim += r[0].transcript;
    }
    onText((finalText + interim).trim());
  };
  rec.onend = onEnd;
  rec.onerror = onEnd;
  rec.start();
  return { stop: () => rec.stop() };
}

export function canSpeak(): boolean {
  return "speechSynthesis" in window;
}

let speakingButton: HTMLElement | null = null;

export function stopSpeaking(): void {
  if (!canSpeak()) return;
  speechSynthesis.cancel();
  speakingButton?.classList.remove("active");
  speakingButton = null;
}

export function speak(markdownText: string, button: HTMLElement): void {
  const wasThisButton = speakingButton === button;
  stopSpeaking();
  if (wasThisButton) return;
  const plain = markdownText
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/[#*_>`~|]/g, "")
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1");
  const utterance = new SpeechSynthesisUtterance(plain);
  utterance.lang = navigator.language || "pt-PT";
  const voice = speechSynthesis.getVoices().find((v) => v.lang.toLowerCase().startsWith(utterance.lang.slice(0, 2).toLowerCase()));
  if (voice) utterance.voice = voice;
  utterance.onend = () => stopSpeaking();
  speakingButton = button;
  button.classList.add("active");
  speechSynthesis.speak(utterance);
}
