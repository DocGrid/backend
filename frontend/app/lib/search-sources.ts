import type { SearchResponse } from "./api-types";

/** UI model that combines multiple search chunks belonging to the same document. */
export type GroupedSearchSource = {
  documentId: number;
  documentTitle: string;
  labels: string[];
  excerpts: string[];
  pages: number[];
  chunkCount: number;
  similarityScore: number | null;
};

type SearchSourceChunk = {
  chunkId: number;
  documentId: number;
  documentTitle: string;
  label: string;
  excerpt: string;
  pageNo: number | null;
  similarityScore: number | null;
};

/**
 * Groups citations by document while preserving the search result order and chunk references.
 *
 * Renders citations only — never falls back to raw search results when citations is empty.
 * An empty citations list is a deliberate signal (no relevant document / RAG judged the
 * retrieved chunks irrelevant), not a data gap to paper over.
 */
export function groupSearchSources(result: SearchResponse): GroupedSearchSource[] {
  const resultByChunk = new Map(result.results.map((item) => [item.chunkId, item]));
  const chunks: SearchSourceChunk[] = result.citations.map((citation) => {
    const matchingResult = resultByChunk.get(citation.chunkId);
    return {
      chunkId: citation.chunkId,
      documentId: citation.documentId,
      documentTitle: citation.documentTitle,
      label: citation.label,
      excerpt: citation.quotedText,
      pageNo: citation.pageNo,
      similarityScore: matchingResult ? Number(matchingResult.similarityScore) : null,
    };
  });

  const groups = new Map<number, GroupedSearchSource & { chunkIds: Set<number> }>();
  for (const chunk of chunks) {
    const existing = groups.get(chunk.documentId);
    if (existing) {
      // A repeated citation for the same chunk should not duplicate the excerpt or count.
      if (existing.chunkIds.has(chunk.chunkId)) continue;
      existing.chunkIds.add(chunk.chunkId);
      existing.labels.push(chunk.label);
      existing.excerpts.push(chunk.excerpt);
      if (chunk.pageNo !== null && !existing.pages.includes(chunk.pageNo)) existing.pages.push(chunk.pageNo);
      existing.chunkCount += 1;
      if (chunk.similarityScore !== null && (existing.similarityScore === null || chunk.similarityScore > existing.similarityScore)) {
        existing.similarityScore = chunk.similarityScore;
      }
      continue;
    }

    groups.set(chunk.documentId, {
      documentId: chunk.documentId,
      documentTitle: chunk.documentTitle,
      labels: [chunk.label],
      excerpts: [chunk.excerpt],
      pages: chunk.pageNo === null ? [] : [chunk.pageNo],
      chunkCount: 1,
      similarityScore: chunk.similarityScore,
      chunkIds: new Set([chunk.chunkId]),
    });
  }

  return Array.from(groups.values()).map((group) => ({
    documentId: group.documentId,
    documentTitle: group.documentTitle,
    labels: group.labels,
    excerpts: group.excerpts,
    pages: group.pages,
    chunkCount: group.chunkCount,
    similarityScore: group.similarityScore,
  }));
}
