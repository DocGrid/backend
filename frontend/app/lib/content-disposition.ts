const FALLBACK_DOWNLOAD_FILENAME = "docgrid-document";

/** Extracts a safe browser download filename from a Content-Disposition header. */
export function parseContentDispositionFilename(header: string | null): string {
  if (!header) return FALLBACK_DOWNLOAD_FILENAME;

  // 1. Prefer RFC 5987 because it carries the filename charset explicitly.
  const extendedFilename = readParameter(header, "filename\\*");
  if (extendedFilename) {
    const encodedValue = extendedFilename.replace(/^[^']*'[^']*'/, "");
    const decodedValue = safelyDecodeUriComponent(encodedValue);
    return sanitizeFilename(decodedValue);
  }

  // 2. Spring uses RFC 2047 encoded words for non-ASCII Content-Disposition filenames.
  const filename = readParameter(header, "filename");
  if (!filename) return FALLBACK_DOWNLOAD_FILENAME;
  return sanitizeFilename(decodeMimeEncodedWords(filename));
}

function readParameter(header: string, parameter: string): string | null {
  const expression = new RegExp(`(?:^|;)\\s*${parameter}\\s*=\\s*(?:"([^"]*)"|([^;]*))`, "i");
  const match = header.match(expression);
  return (match?.[1] ?? match?.[2] ?? "").trim() || null;
}

function safelyDecodeUriComponent(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

function decodeMimeEncodedWords(value: string): string {
  return value.replace(/=\?UTF-8\?([BQ])\?([^?]*)\?=/gi, (encodedWord, encoding: string, payload: string) => {
    try {
      const bytes = encoding.toUpperCase() === "B" ? decodeBase64(payload) : decodeQuotedPrintable(payload);
      return new TextDecoder("utf-8").decode(bytes);
    } catch {
      return encodedWord;
    }
  });
}

function decodeQuotedPrintable(value: string): Uint8Array {
  const bytes: number[] = [];
  for (let index = 0; index < value.length; index += 1) {
    const character = value[index];
    const encodedByte = value.slice(index + 1, index + 3);
    if (character === "=" && /^[0-9A-F]{2}$/i.test(encodedByte)) {
      bytes.push(Number.parseInt(encodedByte, 16));
      index += 2;
    } else {
      bytes.push(character === "_" ? 0x20 : character.charCodeAt(0));
    }
  }
  return Uint8Array.from(bytes);
}

function decodeBase64(value: string): Uint8Array {
  const binary = atob(value);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function sanitizeFilename(value: string): string {
  // 3. Keep only the last path segment so a response cannot choose the download directory.
  const filename = Array.from(value)
    .filter((character) => character.charCodeAt(0) > 0x1f && character.charCodeAt(0) !== 0x7f)
    .join("")
    .split(/[/\\\\]/)
    .at(-1)
    ?.trim();
  return filename && filename !== "." && filename !== ".." ? filename : FALLBACK_DOWNLOAD_FILENAME;
}
