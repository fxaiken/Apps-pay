import type Anthropic from "@anthropic-ai/sdk";

export interface PendingAttachment {
  name: string;
  preview?: string;
  block: Anthropic.Beta.BetaContentBlockParam;
}

const MAX_IMAGE_EDGE = 1568; // larger images are downscaled by the API anyway
const MAX_PDF_BYTES = 30 * 1024 * 1024;
const MAX_TEXT_BYTES = 2 * 1024 * 1024;
const IMAGE_TYPES = ["image/jpeg", "image/png", "image/gif", "image/webp"] as const;
type ImageType = (typeof IMAGE_TYPES)[number];

function readAsDataUrl(file: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(file);
  });
}

function base64Of(dataUrl: string): string {
  return dataUrl.slice(dataUrl.indexOf(",") + 1);
}

async function downscaleImage(file: File): Promise<{ dataUrl: string; type: ImageType }> {
  const original = await readAsDataUrl(file);
  const keepType = (IMAGE_TYPES as readonly string[]).includes(file.type) ? (file.type as ImageType) : "image/jpeg";
  const img = new Image();
  img.src = original;
  await img.decode();
  const scale = Math.min(1, MAX_IMAGE_EDGE / Math.max(img.naturalWidth, img.naturalHeight));
  // GIFs keep their original bytes (animation); everything else is re-encoded when too big or unsupported.
  if (keepType === "image/gif" || (scale === 1 && keepType === file.type && file.size < 4 * 1024 * 1024)) {
    return { dataUrl: original, type: keepType };
  }
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(img.naturalWidth * scale);
  canvas.height = Math.round(img.naturalHeight * scale);
  canvas.getContext("2d")!.drawImage(img, 0, 0, canvas.width, canvas.height);
  return { dataUrl: canvas.toDataURL("image/jpeg", 0.88), type: "image/jpeg" };
}

export async function fileToBlock(file: File): Promise<PendingAttachment> {
  if (file.type.startsWith("image/")) {
    const { dataUrl, type } = await downscaleImage(file);
    return {
      name: file.name,
      preview: dataUrl,
      block: { type: "image", source: { type: "base64", media_type: type, data: base64Of(dataUrl) } },
    };
  }

  if (file.type === "application/pdf" || file.name.toLowerCase().endsWith(".pdf")) {
    if (file.size > MAX_PDF_BYTES) throw new Error(`O PDF "${file.name}" é demasiado grande (máx. 30 MB).`);
    const dataUrl = await readAsDataUrl(file);
    return {
      name: file.name,
      block: {
        type: "document",
        title: file.name,
        source: { type: "base64", media_type: "application/pdf", data: base64Of(dataUrl) },
      },
    };
  }

  if (file.size > MAX_TEXT_BYTES) throw new Error(`O ficheiro "${file.name}" é demasiado grande (máx. 2 MB de texto).`);
  const text = await file.text();
  if (text.includes("\u0000")) throw new Error(`"${file.name}" não parece ser um ficheiro de texto suportado.`);
  return {
    name: file.name,
    block: { type: "document", title: file.name, source: { type: "text", media_type: "text/plain", data: text } },
  };
}
