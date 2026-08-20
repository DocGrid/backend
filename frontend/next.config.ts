import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  experimental: {
    // Vinext가 multipart POST를 Route Handler보다 먼저 검사하므로 백엔드 요청 제한과 맞춘다.
    serverActions: {
      bodySizeLimit: "25mb",
    },
  },
};

export default nextConfig;
