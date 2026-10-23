import { QueryFenceWidget } from "../widget/query-fence.js";
import "../widget/query-fence.js";
document.querySelector<HTMLFormElement>("#connect")!.onsubmit = (e) => {
  e.preventDefault();
  const input = document.querySelector<HTMLInputElement>("#token")!;
  const token = input.value;
  input.value = "";
  try {
    document
      .querySelector<QueryFenceWidget>("query-fence")!
      .configure({
        apiUrl: document.querySelector<HTMLInputElement>("#api-url")!.value,
        theme: "light",
        role: "ANALYST",
        allowedOrigins: [location.origin],
        getAccessToken: () => token,
      });
  } catch (error) {
    document.querySelector("#error")!.textContent =
      error instanceof Error ? error.message : "Configuration failed.";
  }
};
