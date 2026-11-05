// Vite gives the URL of a file that is imported with "?url". The build emits the file as an
// asset, so the service worker caches it for offline use.
declare module "*?url" {
  const url: string;
  export default url;
}
