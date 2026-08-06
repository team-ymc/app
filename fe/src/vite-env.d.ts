/// <reference types="vite/client" />
declare module '*.md?raw' {
  const content: string;
  export default content;
}

declare module 'katex/dist/contrib/auto-render' {
  interface RenderMathInElementOptions {
    delimiters?: Array<{ left: string; right: string; display: boolean }>;
    throwOnError?: boolean;
    ignoredTags?: string[];
    ignoredClasses?: string[];
    errorCallback?: (msg: string, err: Error) => void;
    macros?: Record<string, string>;
  }
  export default function renderMathInElement(
    elem: HTMLElement,
    options?: RenderMathInElementOptions,
  ): void;
}
