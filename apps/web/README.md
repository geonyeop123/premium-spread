This is a [Next.js](https://nextjs.org) project bootstrapped with [`create-next-app`](https://nextjs.org/docs/app/api-reference/cli/create-next-app).

## Getting Started

First, run the development server:

```bash
npm run dev
# or
yarn dev
# or
pnpm dev
# or
bun dev
```

Open [http://localhost:3000](http://localhost:3000) with your browser to see the result.

You can start editing the page by modifying `app/page.tsx`. The page auto-updates as you edit the file.

This project uses [`next/font`](https://nextjs.org/docs/app/building-your-application/optimizing/fonts) to automatically optimize and load [Geist](https://vercel.com/font), a new font family for Vercel.

## Learn More

To learn more about Next.js, take a look at the following resources:

- [Next.js Documentation](https://nextjs.org/docs) - learn about Next.js features and API.
- [Learn Next.js](https://nextjs.org/learn) - an interactive Next.js tutorial.

You can check out [the Next.js GitHub repository](https://github.com/vercel/next.js) - your feedback and contributions are welcome!

## Deploy on Vercel

The easiest way to deploy your Next.js app is to use the [Vercel Platform](https://vercel.com/new?utm_medium=default-template&filter=next.js&utm_source=create-next-app&utm_campaign=create-next-app-readme) from the creators of Next.js.

Check out our [Next.js deployment documentation](https://nextjs.org/docs/app/building-your-application/deploying) for more details.

## 의존성 overrides 정책

`package.json`에는 주석을 쓸 수 없어 `overrides` 정책은 저장소 runbook에 둔다.

- 정본: [`docs/runbooks/deployment.md`](../../docs/runbooks/deployment.md)의 "apps/web 의존성 갱신 (Dependabot)" 절
- 현재 `overrides`는 없다. #97에서 14개를 모두 제거했고, 항목별 판정은 [`docs/work/issue-97-web-npm-overrides/design.md`](../../docs/work/issue-97-web-npm-overrides/design.md) §3에 있다
- `overrides`를 다시 넣을 때는 같은 PR에서 runbook의 overrides 표에 행을 추가한다
