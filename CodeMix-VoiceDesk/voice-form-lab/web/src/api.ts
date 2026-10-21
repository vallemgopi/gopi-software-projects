let authorization = "";
export function credentials(user: string, password: string) {
  authorization = "Basic " + btoa(user + ":" + password);
}
export function clearCredentials() {
  authorization = "";
}
export async function api<T = any>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set("Authorization", authorization);
  if (options.body && !(options.body instanceof FormData))
    headers.set("Content-Type", "application/json");
  const res = await fetch("/api" + path, {
    ...options,
    headers,
    credentials: "omit",
  });
  if (!res.ok) {
    let message = "Request failed (" + res.status + ")";
    try {
      message = (await res.json()).message || message;
    } catch {}
    throw new Error(message);
  }
  return (res.status === 204 ? undefined : await res.json()) as T;
}
export function download(name: string, data: unknown) {
  const url = URL.createObjectURL(
    new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }),
  );
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}
