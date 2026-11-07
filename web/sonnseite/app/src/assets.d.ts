// Vite gives the URL of a file that is imported with "?url". The build emits the file as an
// asset, so the service worker caches it for offline use.
declare module "*?url" {
  const url: string;
  export default url;
}

// Vite gives the text of a file that is imported with "?raw", in the bundle.
declare module "*?raw" {
  const text: string;
  export default text;
}
