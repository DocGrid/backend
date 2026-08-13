import type { Metadata } from "next";
import { headers } from "next/headers";
import "./globals.css";

export async function generateMetadata(): Promise<Metadata> {
  const requestHeaders = await headers();
  const host = requestHeaders.get("x-forwarded-host") ?? requestHeaders.get("host") ?? "localhost:3000";
  const protocol = requestHeaders.get("x-forwarded-proto") ?? (host.startsWith("localhost") ? "http" : "https");
  const previewImage = `${protocol}://${host}/og.png`;

  return {
    title: "DocGrid — 팀의 지식에서 정확한 답을",
    description: "문서 검색, 컬렉션, 권한, MCP 연동과 RAG 운영을 한곳에서 관리하는 지식 검색 워크스페이스",
    openGraph: {
      title: "DocGrid — 팀의 지식에서 정확한 답을",
      description: "흩어진 사내 문서에서 근거가 포함된 정확한 답을 찾으세요.",
      images: [{ url: previewImage, width: 1733, height: 908, alt: "DocGrid 지식 검색" }],
    },
    twitter: {
      card: "summary_large_image",
      title: "DocGrid — 팀의 지식에서 정확한 답을",
      description: "흩어진 사내 문서에서 근거가 포함된 정확한 답을 찾으세요.",
      images: [previewImage],
    },
  };
}

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
