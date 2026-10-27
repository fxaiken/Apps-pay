# Claude Chat

App de chat com o Claude (Anthropic), para web e Android, com:

- **Pesquisa na internet** com fontes citadas
- **Ficheiros e imagens**: fotos, PDFs e ficheiros de texto/código
- **Personas**: assistentes com instruções próprias (incluídas: Assistente, Programador, Professor, Tradutor)
- **Memória entre conversas**: o Claude guarda factos sobre si (pode ver e apagar em 🧠 Memória)
- Respostas em tempo real, raciocínio visível, histórico guardado no dispositivo, tema claro/escuro

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
