"use strict";

const MAX_OUTPUT_CHARACTERS = 100000;

const elements = {
  clearOutput: document.querySelector("#clear-output"),
  connectionStatus: document.querySelector("#connection-status"),
  output: document.querySelector("#output"),
  refreshRoutes: document.querySelector("#refresh-routes"),
  requestJson: document.querySelector("#request-json"),
  routeList: document.querySelector("#route-list"),
  runForm: document.querySelector("#run-form"),
  startRun: document.querySelector("#start-run"),
  stopRun: document.querySelector("#stop-run"),
  streamResponse: document.querySelector("#stream-response"),
  target: document.querySelector("#target"),
};

const state = {
  activeController: null,
  configuration: null,
  routes: [],
};

function setStatus(message, kind) {
  elements.connectionStatus.textContent = message;
  elements.connectionStatus.className = `status ${kind || ""}`.trim();
}

function setOutput(value) {
  const text = String(value);
  elements.output.textContent =
    text.length > MAX_OUTPUT_CHARACTERS
      ? `${text.slice(text.length - MAX_OUTPUT_CHARACTERS)}\n[older output truncated]`
      : text;
  elements.output.scrollTop = elements.output.scrollHeight;
}

function appendOutput(value) {
  const current = elements.output.textContent === "Waiting for a request." ? "" : elements.output.textContent;
  setOutput(`${current}${current ? "\n" : ""}${value}`);
}

function routeKey(route) {
  return `${route.collection}/${route.id}`;
}

function renderRoutes() {
  elements.routeList.replaceChildren();
  elements.target.replaceChildren();

  if (state.routes.length === 0) {
    const empty = document.createElement("p");
    empty.textContent = "No generic hosting routes are registered.";
    elements.routeList.append(empty);

    const option = document.createElement("option");
    option.value = "";
    option.textContent = "No routes available";
    elements.target.append(option);
    elements.target.disabled = true;
    elements.startRun.disabled = true;
    return;
  }

  for (const route of state.routes) {
    const card = document.createElement("article");
    card.className = "route-card";

    const kind = document.createElement("span");
    kind.className = "route-kind";
    kind.textContent = route.collection;

    const name = document.createElement("strong");
    name.textContent = route.name || route.id;

    const detail = document.createElement("span");
    const capabilities = [
      route.streamingSupported ? "SSE" : "finite only",
      route.resumeSupported ? "resume" : null,
    ]
      .filter(Boolean)
      .join(" · ");
    detail.textContent = `${route.id} · ${capabilities}${route.description ? ` · ${route.description}` : ""}`;

    card.append(kind, name, detail);
    elements.routeList.append(card);

    const option = document.createElement("option");
    option.value = routeKey(route);
    option.textContent = `${route.collection} / ${route.name || route.id}`;
    elements.target.append(option);
  }

  elements.target.disabled = false;
  elements.startRun.disabled = false;
}

async function readError(response) {
  const body = await response.text();
  try {
    return JSON.stringify(JSON.parse(body), null, 2);
  } catch {
    return body || `${response.status} ${response.statusText}`;
  }
}

async function loadConfiguration() {
  const response = await fetch("./config.json", {
    cache: "no-store",
    credentials: "same-origin",
    headers: { Accept: "application/json" },
  });
  if (!response.ok) {
    throw new Error(await readError(response));
  }
  const configuration = await response.json();
  if (
    configuration.sameOrigin !== true ||
    configuration.apiBasePath !== "/v1" ||
    !Array.isArray(configuration.collections)
  ) {
    throw new Error("Developer UI configuration is invalid.");
  }
  state.configuration = configuration;
}

async function loadRoutes() {
  elements.refreshRoutes.disabled = true;
  setStatus("Loading routes…", "");
  try {
    if (!state.configuration) {
      await loadConfiguration();
    }
    const groups = await Promise.all(
      state.configuration.collections.map(async (collection) => {
        const response = await fetch(`${state.configuration.apiBasePath}/${collection}`, {
          cache: "no-store",
          credentials: "same-origin",
          headers: { Accept: "application/json" },
        });
        if (!response.ok) {
          throw new Error(`${collection}: ${await readError(response)}`);
        }
        const body = await response.json();
        return Array.isArray(body.items)
          ? body.items.map((route) => ({ ...route, collection }))
          : [];
      }),
    );
    state.routes = groups.flat();
    renderRoutes();
    setStatus(`Connected · ${state.routes.length} route${state.routes.length === 1 ? "" : "s"}`, "connected");
  } catch (error) {
    state.routes = [];
    renderRoutes();
    setStatus("Connection failed", "error");
    setOutput(error instanceof Error ? error.message : String(error));
  } finally {
    elements.refreshRoutes.disabled = false;
  }
}

function selectedRoute() {
  return state.routes.find((route) => routeKey(route) === elements.target.value);
}

function setRunning(running) {
  elements.startRun.disabled = running || state.routes.length === 0;
  elements.stopRun.disabled = !running;
  elements.refreshRoutes.disabled = running;
  elements.target.disabled = running || state.routes.length === 0;
}

function formatSseFrame(frame) {
  const lines = frame.split("\n");
  let event = "message";
  const data = [];
  for (const line of lines) {
    if (line.startsWith("event:")) {
      event = line.slice("event:".length).trim();
    } else if (line.startsWith("data:")) {
      data.push(line.slice("data:".length).trimStart());
    }
  }
  const joined = data.join("\n");
  if (!joined) {
    return `[${event}]`;
  }
  try {
    return `[${event}]\n${JSON.stringify(JSON.parse(joined), null, 2)}`;
  } catch {
    return `[${event}]\n${joined}`;
  }
}

async function readSse(response) {
  if (!response.body) {
    throw new Error("Streaming response body is unavailable.");
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (true) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done }).replaceAll("\r\n", "\n");
    let boundary = buffer.indexOf("\n\n");
    while (boundary >= 0) {
      const frame = buffer.slice(0, boundary).trim();
      buffer = buffer.slice(boundary + 2);
      if (frame) {
        appendOutput(formatSseFrame(frame));
      }
      boundary = buffer.indexOf("\n\n");
    }
    if (done) {
      if (buffer.trim()) {
        appendOutput(formatSseFrame(buffer.trim()));
      }
      return;
    }
  }
}

async function runTarget(event) {
  event.preventDefault();
  const route = selectedRoute();
  if (!route || !state.configuration) {
    return;
  }

  const controller = new AbortController();
  state.activeController = controller;
  setRunning(true);
  setOutput(`POST ${state.configuration.apiBasePath}/${route.collection}/${route.id}/runs`);

  const streaming = elements.streamResponse.checked && route.streamingSupported;
  const suffix = streaming ? "/stream" : "";
  try {
    const response = await fetch(
      `${state.configuration.apiBasePath}/${route.collection}/${route.id}/runs${suffix}`,
      {
        method: "POST",
        cache: "no-store",
        credentials: "same-origin",
        headers: {
          Accept: streaming ? "text/event-stream" : "application/json",
          "Content-Type": "application/json",
        },
        body: elements.requestJson.value,
        signal: controller.signal,
      },
    );
    if (!response.ok) {
      throw new Error(await readError(response));
    }
    if (streaming) {
      await readSse(response);
    } else {
      const body = await response.json();
      appendOutput(JSON.stringify(body, null, 2));
    }
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      appendOutput("[request cancelled]");
    } else {
      appendOutput(error instanceof Error ? error.message : String(error));
    }
  } finally {
    if (state.activeController === controller) {
      state.activeController = null;
    }
    setRunning(false);
  }
}

elements.clearOutput.addEventListener("click", () => setOutput("Waiting for a request."));
elements.refreshRoutes.addEventListener("click", loadRoutes);
elements.runForm.addEventListener("submit", runTarget);
elements.stopRun.addEventListener("click", () => state.activeController?.abort());

loadRoutes();
