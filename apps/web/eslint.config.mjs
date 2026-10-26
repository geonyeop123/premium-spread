import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  // react-hooks 7.1 의 set-state-in-effect 가 effect 내 조회 호출을 잡는다 — 조회 패턴 개선은 별도 이슈 (issue #82 D7)
  {
    files: ["src/app/trackings/page.tsx", "src/app/trackings/[[]id[]]/page.tsx"],
    rules: { "react-hooks/set-state-in-effect": "off" },
  },
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
  ]),
]);

export default eslintConfig;
