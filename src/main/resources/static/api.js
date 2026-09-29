export class ApiError extends Error {
  constructor(status, code, message, path) {
    super(message || `请求失败（HTTP ${status}）`);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.path = path;
  }
}

async function request(path, options = {}) {
  let response;
  try {
    response = await fetch(path, {
      ...options,
      headers: {
        Accept: "application/json",
        ...(options.body ? { "Content-Type": "application/json" } : {}),
        ...options.headers,
      },
    });
  } catch (error) {
    throw new Error("无法连接服务，请确认 Docker Compose 中的应用容器正在运行后重试。");
  }

  const bodyText = await response.text();
  let body = null;
  if (bodyText) {
    try {
      body = JSON.parse(bodyText);
    } catch {
      body = null;
    }
  }
  if (!response.ok) {
    throw new ApiError(response.status, body?.code, body?.message, body?.path);
  }
  return body;
}

function queryString(values) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined && value !== null && value !== "") params.set(key, String(value));
  }
  const query = params.toString();
  return query ? `?${query}` : "";
}

export const api = {
  health: () => request("/actuator/health"),
  products: (filters = {}) => request(`/api/products${queryString({
    q: filters.q,
    saleMode: filters.saleMode,
    active: filters.active,
    page: filters.page ?? 0,
    size: filters.size ?? 20,
  })}`),
  product: (productId) => request(`/api/products/${encodeURIComponent(productId)}`),
  createProduct: (payload) => request("/api/products", { method: "POST", body: JSON.stringify(payload) }),
  updateProduct: (productId, payload) => request(`/api/products/${encodeURIComponent(productId)}`, {
    method: "PUT",
    body: JSON.stringify(payload),
  }),
  deactivateProduct: (productId) => request(`/api/products/${encodeURIComponent(productId)}`, { method: "DELETE" }),
  inventory: (productId) => request(`/api/inventory/${encodeURIComponent(productId)}`),
  adjustInventory: (productId, payload) => request(`/api/inventory/${encodeURIComponent(productId)}/adjustments`, {
    method: "POST",
    body: JSON.stringify(payload),
  }),
  movements: (productId, page = 0, size = 20) => request(
    `/api/inventory/${encodeURIComponent(productId)}/movements${queryString({ page, size })}`,
  ),
  orders: (filters = {}) => request(`/api/orders${queryString({
    status: filters.status,
    page: filters.page ?? 0,
    size: filters.size ?? 20,
  })}`),
  order: (orderId) => request(`/api/orders/${encodeURIComponent(orderId)}`),
  createOrder: (payload, idempotencyKey) => request("/api/orders", {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify(payload),
  }),
  cancelOrder: (orderId) => request(`/api/orders/${encodeURIComponent(orderId)}/cancel`, { method: "POST" }),
};
