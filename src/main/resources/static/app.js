import { ApiError, api } from "./api.js";

const PAGE_SIZE = 20;
const PRODUCT_ID_PATTERN = /^[A-Za-z0-9._-]{1,64}$/;
const viewInfo = {
  products: ["商品管理", "维护商品目录和销售规则，库存数量通过库存调整单独管理。"],
  inventory: ["库存管理", "查看实物库存、普通销售预留和预售名额，并记录每次调整。"],
  orders: ["订单管理", "创建多商品库存预留订单，查看历史记录并释放未完成订单的预留。"],
};

const state = {
  view: "products",
  products: { page: 0, totalPages: 0, totalElements: 0, items: [], q: "", saleMode: "", active: "true", requestId: 0 },
  orders: { page: 0, totalPages: 0, totalElements: 0, items: [], status: "", requestId: 0 },
  movements: { page: 0, totalPages: 0, totalElements: 0, items: [], requestId: 0 },
  inventoryProductId: "",
  activeProducts: [],
  productCache: new Map(),
  inventorySnapshots: new Map(),
  pendingOrderIntent: null,
  activeProduct: null,
  activeOrder: null,
  busy: new Set(),
  orderLineSequence: 0,
};

const $ = (id) => document.getElementById(id);
const escapeHtml = (value) => String(value ?? "").replace(/[&<>"']/g, (character) => ({
  "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
})[character]);
const numberFormat = new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 0 });
const moneyFormat = new Intl.NumberFormat("zh-CN", { style: "currency", currency: "CNY", minimumFractionDigits: 2, maximumFractionDigits: 2 });
const formatCount = (value) => numberFormat.format(Number(value ?? 0));
const formatMoney = (value) => moneyFormat.format(Number(value ?? 0));
const formatDate = (value) => value ? new Intl.DateTimeFormat("zh-CN", {
  year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false,
}).format(new Date(value)) : "—";
const saleModeLabel = (mode) => mode === "PRESALE" ? "限额预售" : "普通销售";
const statusLabel = (status) => status === "CANCELLED" ? "已取消" : "库存已预留";

function showToast(message, type = "success") {
  const toast = document.createElement("div");
  toast.className = `toast ${type === "error" ? "error" : type === "warning" ? "warning" : ""}`;
  toast.setAttribute("role", type === "error" ? "alert" : "status");
  toast.textContent = message;
  $("toast-region").append(toast);
  window.setTimeout(() => toast.remove(), 5200);
}

function explainError(error, action = "操作") {
  if (error instanceof ApiError) {
    if (error.status === 409) {
      if (error.message.includes("普通库存不足")) return `普通销售可用库存不足，${action}未完成。请刷新库存或减少数量后重试。`;
      if (error.message.includes("预售名额不足")) return `预售名额不足，${action}未完成。请查看当前已预留名额后重试。`;
      if (error.message.includes("Idempotency-Key")) return "该幂等键已用于不同的订单内容。请重新开始一次创建操作。";
      return `当前业务状态不允许${action}：${error.message}`;
    }
    if (error.status === 404) return `找不到对应的商品或订单：${error.message}`;
    if (error.status === 400) return `请求内容不符合服务端校验规则：${error.message}`;
    return error.message || `${action}失败（HTTP ${error.status}）。`;
  }
  return error?.message || `${action}失败，请稍后重试。`;
}

function setFeedback(element, message, kind = "error") {
  element.textContent = message;
  element.className = `inline-feedback${kind === "success" ? " success" : ""}`;
  element.hidden = false;
}

function clearFeedback(element) {
  element.textContent = "";
  element.hidden = true;
}

function setDialogFeedback(element, message) {
  element.textContent = message;
  element.hidden = false;
}

function clearDialogFeedback(element) {
  element.textContent = "";
  element.hidden = true;
}

function setBusy(button, busy, busyText) {
  if (busy) {
    button.dataset.originalText = button.textContent;
    button.disabled = true;
    button.textContent = busyText;
  } else {
    button.disabled = false;
    if (button.dataset.originalText) button.textContent = button.dataset.originalText;
    delete button.dataset.originalText;
  }
}

function setHealth(up) {
  const badge = $("health-badge");
  badge.classList.toggle("is-up", up);
  badge.classList.toggle("is-down", !up);
  badge.classList.remove("is-loading");
  badge.lastElementChild.textContent = up ? "服务正常" : "服务未连接";
}

async function checkHealth() {
  try {
    const result = await api.health();
    setHealth(result?.status === "UP");
  } catch {
    setHealth(false);
  }
}

function switchView(view, updateHash = true) {
  if (!Object.hasOwn(viewInfo, view)) view = "products";
  state.view = view;
  document.querySelectorAll("[data-panel]").forEach((panel) => { panel.hidden = panel.dataset.panel !== view; });
  document.querySelectorAll(".nav-item").forEach((button) => button.classList.toggle("is-active", button.dataset.view === view));
  $("page-title").textContent = viewInfo[view][0];
  $("breadcrumb-current").textContent = viewInfo[view][0];
  $("page-description").textContent = viewInfo[view][1];
  document.title = `${viewInfo[view][0]} - 商品库存管理台`;
  if (updateHash && window.location.hash !== `#${view}`) window.history.replaceState(null, "", `#${view}`);
  if (view === "products") loadProducts();
  if (view === "orders") loadOrders();
}

function productRow(product) {
  const presale = product.presaleLimit == null
    ? "—"
    : `${formatCount(product.presaleLimit)} 名额 <span class="muted-text">/ ${formatCount(product.presaleReservedQuantity)} 已预留</span>`;
  return `<tr data-product-row="${escapeHtml(product.productId)}">
    <td data-label="商品"><div class="product-name-cell"><strong>${escapeHtml(product.name)}</strong><small class="code-text">${escapeHtml(product.productId)}</small></div></td>
    <td data-label="销售模式"><span class="mode-pill ${product.saleMode === "PRESALE" ? "presale" : ""}">${saleModeLabel(product.saleMode)}</span></td>
    <td data-label="价格" class="number-cell">${formatMoney(product.price)}</td>
    <td data-label="实物库存" class="number-cell"><strong>${formatCount(product.onHandQuantity)}</strong></td>
    <td data-label="普通销售"><span class="number-cell">${formatCount(product.availableRegularQuantity)} 可用</span><br><span class="muted-text">${formatCount(product.regularReservedQuantity)} 已预留</span></td>
    <td data-label="预售名额 / 已预留"><span class="number-cell">${presale}</span></td>
    <td data-label="状态"><span class="status-pill ${product.active ? "" : "inactive"}">${product.active ? "启用中" : "已停用"}</span></td>
    <td data-label="操作"><div class="action-buttons"><button class="action-link" type="button" data-product-action="details" data-product-id="${escapeHtml(product.productId)}">查看</button><button class="action-link" type="button" data-product-action="edit" data-product-id="${escapeHtml(product.productId)}">编辑</button><button class="action-link" type="button" data-product-action="inventory" data-product-id="${escapeHtml(product.productId)}">库存</button>${product.active ? `<button class="action-link danger" type="button" data-product-action="deactivate" data-product-id="${escapeHtml(product.productId)}">停用</button>` : ""}</div></td>
  </tr>`;
}

function renderProductMetrics(items) {
  $("metric-product-count").textContent = formatCount(items.length);
  $("metric-on-hand").textContent = formatCount(items.reduce((total, item) => total + item.onHandQuantity, 0));
  $("metric-available").textContent = formatCount(items.reduce((total, item) => total + item.availableRegularQuantity, 0));
  $("metric-presale").textContent = formatCount(items.reduce((total, item) => total + item.presaleReservedQuantity, 0));
}

function renderProductPage(result) {
  state.products.items = result.items;
  state.products.totalPages = result.totalPages;
  state.products.totalElements = result.totalElements;
  result.items.forEach((item) => state.productCache.set(item.productId, item));
  $("products-table-body").innerHTML = result.items.map(productRow).join("");
  $("products-empty").hidden = result.items.length !== 0;
  $("products-table").hidden = result.items.length === 0;
  $("products-count").textContent = result.totalElements ? `共 ${formatCount(result.totalElements)} 件商品` : "共 0 件商品";
  $("products-page-label").textContent = result.totalPages ? `${result.page + 1} / ${result.totalPages}` : "0 / 0";
  $("products-prev").disabled = result.page <= 0;
  $("products-next").disabled = result.totalPages === 0 || result.page + 1 >= result.totalPages;
  renderProductMetrics(result.items);
  updateProductSuggestions();
}

async function loadProducts() {
  const requestId = ++state.products.requestId;
  $("products-loading").hidden = false;
  $("products-empty").hidden = true;
  $("products-table").hidden = true;
  clearFeedback($("product-feedback"));
  try {
    const result = await api.products({
      q: state.products.q,
      saleMode: state.products.saleMode,
      active: state.products.active,
      page: state.products.page,
      size: PAGE_SIZE,
    });
    if (requestId === state.products.requestId) renderProductPage(result);
  } catch (error) {
    if (requestId === state.products.requestId) setFeedback($("product-feedback"), explainError(error, "查询商品"));
  } finally {
    if (requestId === state.products.requestId) $("products-loading").hidden = true;
  }
}

async function loadActiveCatalog() {
  try {
    const result = await api.products({ active: "true", page: 0, size: 100 });
    state.activeProducts = result.items;
    result.items.forEach((item) => state.productCache.set(item.productId, item));
    updateProductSuggestions();
  } catch {
    state.activeProducts = [];
  }
}

function updateProductSuggestions() {
  const products = new Map(state.activeProducts.map((product) => [product.productId, product]));
  for (const product of state.products.items) products.set(product.productId, product);
  const options = [...products.values()].map((product) => `<option value="${escapeHtml(product.productId)}">${escapeHtml(product.name)}</option>`).join("");
  $("product-suggestions").innerHTML = options;
  $("order-product-suggestions").innerHTML = state.activeProducts.map((product) =>
    `<option value="${escapeHtml(product.productId)}">${escapeHtml(product.name)} · ${saleModeLabel(product.saleMode)}</option>`).join("");
}

function updateSaleModeFields() {
  const presale = $("product-sale-mode").value === "PRESALE";
  $("presale-limit-field").hidden = !presale;
  $("product-presale-limit").required = presale;
  $("initial-stock-field").hidden = state.productDialogMode === "edit";
}

function openProductDialog(product = null) {
  state.productDialogMode = product ? "edit" : "create";
  const form = $("product-form");
  form.reset();
  clearDialogFeedback($("product-dialog-feedback"));
  $("product-dialog-title").textContent = product ? "编辑商品" : "新建商品";
  $("save-product-button").textContent = product ? "保存修改" : "创建商品";
  $("product-id").disabled = Boolean(product);
  $("product-id").value = product?.productId ?? "";
  $("product-name").value = product?.name ?? "";
  $("product-price").value = product?.price ?? "";
  $("product-sale-mode").value = product?.saleMode ?? "REGULAR";
  $("product-presale-limit").value = product?.presaleLimit ?? "";
  $("product-initial-stock").value = "0";
  $("product-description").value = product?.description ?? "";
  $("active-field").hidden = !product;
  $("product-active-toggle").checked = product?.active ?? true;
  updateSaleModeFields();
  $("product-dialog").showModal();
}

async function viewProduct(productId) {
  try {
    const product = await api.product(productId);
    state.activeProduct = product;
    state.productCache.set(product.productId, product);
    $("product-detail-title").textContent = product.name;
    $("product-detail-content").innerHTML = `<div class="detail-summary">
      <article><span>單價</span><strong>${formatMoney(product.price)}</strong></article>
      <article><span>實物庫存</span><strong>${formatCount(product.onHandQuantity)}</strong></article>
      <article><span>普通銷售可用</span><strong>${formatCount(product.availableRegularQuantity)}</strong></article>
    </div><dl class="detail-list">
      <dt>商品編號</dt><dd class="code-text">${escapeHtml(product.productId)}</dd>
      <dt>銷售模式</dt><dd>${saleModeLabel(product.saleMode)}</dd>
      <dt>啟用狀態</dt><dd>${product.active ? "啟用中" : "已停用"}</dd>
      <dt>普通销售预留</dt><dd>${formatCount(product.regularReservedQuantity)}</dd>
      <dt>预售名额 / 已预留</dt><dd>${product.presaleLimit == null ? "—" : `${formatCount(product.presaleLimit)} / ${formatCount(product.presaleReservedQuantity)}`}</dd>
    <dt>创建时间</dt><dd>${formatDate(product.createdAt)}</dd>
      <dt>最近更新</dt><dd>${formatDate(product.updatedAt)}</dd>
    </dl>${product.description ? `<div class="detail-description">${escapeHtml(product.description)}</div>` : ""}`;
    $("product-detail-dialog").showModal();
  } catch (error) {
    showToast(explainError(error, "查询商品详情"), "error");
  }
}

async function submitProduct(event) {
  event.preventDefault();
  const form = event.currentTarget;
  if (!form.reportValidity()) return;
  const isEdit = state.productDialogMode === "edit";
  const saleMode = $("product-sale-mode").value;
  const payload = {
    name: $("product-name").value.trim(),
    description: $("product-description").value.trim() || null,
    price: Number($("product-price").value),
    saleMode,
    presaleLimit: saleMode === "PRESALE" ? Number($("product-presale-limit").value) : null,
  };
  if (isEdit) payload.active = $("product-active-toggle").checked;
  else {
    payload.productId = $("product-id").value.trim();
    payload.initialStock = Number($("product-initial-stock").value || 0);
  }

  const button = $("save-product-button");
  setBusy(button, true, "正在保存…");
  clearDialogFeedback($("product-dialog-feedback"));
  try {
    const saved = isEdit
      ? await api.updateProduct($("product-id").value, payload)
      : await api.createProduct(payload);
    state.productCache.set(saved.productId, saved);
    $("product-dialog").close();
    showToast(isEdit ? "商品信息已保存。" : "商品已创建。库存数据已从服务端刷新。");
    await Promise.all([loadProducts(), loadActiveCatalog()]);
    if (state.inventoryProductId === saved.productId) await loadInventory(saved.productId);
  } catch (error) {
    setDialogFeedback($("product-dialog-feedback"), explainError(error, isEdit ? "保存商品" : "创建商品"));
  } finally {
    setBusy(button, false);
  }
}

async function deactivateProduct(productId) {
  const product = state.productCache.get(productId);
  if (!window.confirm(`确认停用「${product?.name ?? productId}」？历史订单和库存流水会保留，停用后不能创建新订单。`)) return;
  try {
    await api.deactivateProduct(productId);
    showToast("商品已停用，历史记录已保留。");
    await Promise.all([loadProducts(), loadActiveCatalog()]);
  } catch (error) {
    showToast(explainError(error, "停用商品"), "error");
  }
}

function movementTypeLabel(type) {
  return ({
    INITIAL_STOCK: "商品初始库存",
    STOCK_ADJUSTMENT: "实物库存调整",
    ORDER_RESERVED: "订单库存预留",
    ORDER_CANCELLED: "取消并释放预留",
  })[type] ?? type;
}

function signedNumber(value) {
  if (value === 0) return "—";
  return `${value > 0 ? "+" : "−"}${formatCount(Math.abs(value))}`;
}

function movementRow(movement) {
  const physicalClass = movement.physicalDelta > 0 ? "delta-positive" : movement.physicalDelta < 0 ? "delta-negative" : "";
  return `<tr>
    <td data-label="时间 / 类型"><div class="movement-main"><strong>${movementTypeLabel(movement.movementType)}</strong><small>${formatDate(movement.createdAt)}</small></div></td>
    <td data-label="实物变化" class="number-cell ${physicalClass}">${signedNumber(movement.physicalDelta)}</td>
    <td data-label="普通预留变化" class="number-cell">${signedNumber(movement.regularReservedDelta)}</td>
    <td data-label="预售预留变化" class="number-cell">${signedNumber(movement.presaleReservedDelta)}</td>
    <td data-label="变更后计数"><div class="movement-main"><strong>实物 ${formatCount(movement.onHandAfter)}</strong><small>普通预留 ${formatCount(movement.regularReservedAfter)} · 预售预留 ${formatCount(movement.presaleReservedAfter)}</small></div></td>
    <td data-label="原因 / 关联单号"><div class="movement-main"><span>${escapeHtml(movement.reason)}</span>${movement.referenceId ? `<small class="reference-id" title="${escapeHtml(movement.referenceId)}">${escapeHtml(movement.referenceId)}</small>` : ""}</div></td>
  </tr>`;
}

async function loadMovements(productId = state.inventoryProductId) {
  if (!productId) return;
  const requestId = ++state.movements.requestId;
  $("movements-loading").hidden = false;
  $("movements-empty").hidden = true;
  try {
    const result = await api.movements(productId, state.movements.page, PAGE_SIZE);
    if (requestId !== state.movements.requestId) return;
    state.movements.items = result.items;
    state.movements.totalPages = result.totalPages;
    state.movements.totalElements = result.totalElements;
    $("movements-table-body").innerHTML = result.items.map(movementRow).join("");
    $("movements-empty").hidden = result.items.length !== 0;
    $("movements-count").textContent = `共 ${formatCount(result.totalElements)} 条流水`;
    $("movements-page-label").textContent = result.totalPages ? `${result.page + 1} / ${result.totalPages}` : "0 / 0";
    $("movements-prev").disabled = result.page <= 0;
    $("movements-next").disabled = result.totalPages === 0 || result.page + 1 >= result.totalPages;
  } catch (error) {
    if (requestId === state.movements.requestId) {
      setFeedback($("inventory-feedback"), explainError(error, "查询库存流水"));
    }
  } finally {
    if (requestId === state.movements.requestId) $("movements-loading").hidden = true;
  }
}

function renderInventory(inventory) {
  state.inventorySnapshots.set(inventory.productId, inventory);
  $("inventory-content").hidden = false;
  $("inventory-product-title").textContent = inventory.productId;
  $("inventory-product-status").textContent = inventory.active ? "启用中" : "已停用";
  $("inventory-product-status").classList.toggle("inactive", !inventory.active);
  $("inventory-on-hand").textContent = formatCount(inventory.onHandQuantity);
  $("inventory-regular-reserved").textContent = formatCount(inventory.regularReservedQuantity);
  $("inventory-available").textContent = formatCount(inventory.availableRegularQuantity);
  $("inventory-presale").textContent = inventory.presaleLimit == null
    ? "—"
    : `${formatCount(inventory.presaleLimit)} / ${formatCount(inventory.presaleReservedQuantity)}`;
}

async function loadInventory(productId) {
  if (!PRODUCT_ID_PATTERN.test(productId)) {
    setFeedback($("inventory-feedback"), "商品编号格式无效，请使用字母、数字、点、下划线或连字符。 ");
    return;
  }
  state.inventoryProductId = productId;
  state.movements.page = 0;
  $("inventory-product-id").value = productId;
  clearFeedback($("inventory-feedback"));
  $("inventory-content").hidden = false;
  $("inventory-on-hand").textContent = "…";
  try {
    const inventory = await api.inventory(productId);
    if (state.inventoryProductId !== productId) return;
    renderInventory(inventory);
    await loadMovements(productId);
  } catch (error) {
    if (state.inventoryProductId === productId) {
      $("inventory-content").hidden = true;
      setFeedback($("inventory-feedback"), explainError(error, "查询库存"));
    }
  }
}

async function submitStockAdjustment(event) {
  event.preventDefault();
  const form = event.currentTarget;
  if (!state.inventoryProductId) {
    setFeedback($("inventory-feedback"), "请先选择商品并查询库存。");
    return;
  }
  if (!form.reportValidity()) return;
  const quantityDelta = Number($("stock-delta").value);
  if (!Number.isInteger(quantityDelta) || quantityDelta === 0) {
    setFeedback($("inventory-feedback"), "数量差异必须是非零整数。");
    return;
  }
  const button = form.querySelector("button[type=submit]");
  setBusy(button, true, "正在记录…");
  try {
    await api.adjustInventory(state.inventoryProductId, {
      quantityDelta,
      reason: $("stock-reason").value,
    });
    form.reset();
    $("reason-length").textContent = "0";
    await Promise.all([loadInventory(state.inventoryProductId), loadProducts()]);
    showToast("实物库存调整已记录，库存概览和流水已刷新。");
  } catch (error) {
    setFeedback($("inventory-feedback"), explainError(error, "调整实物库存"));
  } finally {
    setBusy(button, false);
  }
}

function orderRow(order) {
  const products = order.items.map((item) => `${escapeHtml(item.productName)} × ${formatCount(item.quantity)}`);
  const productSummary = products.slice(0, 2).join("、") + (products.length > 2 ? ` 等 ${products.length} 种商品` : "");
  return `<tr>
    <td data-label="订单编号"><div class="order-id"><strong>${escapeHtml(order.orderId)}</strong><small>${formatDate(order.updatedAt)}</small></div></td>
    <td data-label="商品"><div class="order-product-cell"><strong>${productSummary}</strong><small>${order.items.map((item) => saleModeLabel(item.saleMode)).join(" · ")}</small></div></td>
    <td data-label="商品数" class="number-cell">${formatCount(order.items.length)} 种 / ${formatCount(order.items.reduce((n, item) => n + item.quantity, 0))} 件</td>
    <td data-label="订单金额" class="number-cell"><strong>${formatMoney(order.totalAmount)}</strong></td>
    <td data-label="状态"><span class="status-pill ${order.status === "CANCELLED" ? "inactive" : ""}">${statusLabel(order.status)}</span></td>
    <td data-label="创建时间"><span class="muted-text">${formatDate(order.createdAt)}</span></td>
    <td data-label="操作"><div class="action-buttons"><button class="action-link" type="button" data-order-action="details" data-order-id="${escapeHtml(order.orderId)}">查看详情</button></div></td>
  </tr>`;
}

async function loadOrders() {
  const requestId = ++state.orders.requestId;
  $("orders-loading").hidden = false;
  $("orders-table").hidden = true;
  $("orders-empty").hidden = true;
  clearFeedback($("orders-feedback"));
  try {
    const result = await api.orders({ status: state.orders.status, page: state.orders.page, size: PAGE_SIZE });
    if (requestId !== state.orders.requestId) return;
    state.orders.items = result.items;
    state.orders.totalPages = result.totalPages;
    state.orders.totalElements = result.totalElements;
    $("orders-table-body").innerHTML = result.items.map(orderRow).join("");
    $("orders-empty").hidden = result.items.length !== 0;
    $("orders-table").hidden = result.items.length === 0;
    $("orders-count").textContent = `共 ${formatCount(result.totalElements)} 笔订单`;
    $("orders-page-label").textContent = result.totalPages ? `${result.page + 1} / ${result.totalPages}` : "0 / 0";
    $("orders-prev").disabled = result.page <= 0;
    $("orders-next").disabled = result.totalPages === 0 || result.page + 1 >= result.totalPages;
    $("metric-order-count").textContent = formatCount(result.items.length);
    $("metric-order-value").textContent = formatMoney(result.items.reduce((total, order) => total + Number(order.totalAmount), 0));
  } catch (error) {
    if (requestId === state.orders.requestId) setFeedback($("orders-feedback"), explainError(error, "查询订单"));
  } finally {
    if (requestId === state.orders.requestId) $("orders-loading").hidden = true;
  }
}

async function openOrderDetail(orderId) {
  try {
    const order = await api.order(orderId);
    state.activeOrder = order;
    renderOrderDetail(order);
    $("order-detail-dialog").showModal();
  } catch (error) {
    showToast(explainError(error, "查询订单详情"), "error");
  }
}

function renderOrderDetail(order) {
  $("order-detail-title").textContent = `订单 ${order.orderId.slice(0, 8)}`;
  const itemRows = order.items.map((item) => `<tr>
    <td>${escapeHtml(item.productName)}<br><span class="muted-text code-text">${escapeHtml(item.productId)}</span></td>
    <td>${saleModeLabel(item.saleMode)}</td><td>${formatCount(item.quantity)}</td>
    <td>${formatMoney(item.unitPrice)}</td><td>${formatMoney(item.lineTotal)}</td>
  </tr>`).join("");
  $("order-detail-content").innerHTML = `<div class="order-detail-head"><div class="order-id"><strong>${escapeHtml(order.orderId)}</strong><small>创建于 ${formatDate(order.createdAt)}</small></div><span class="status-pill ${order.status === "CANCELLED" ? "inactive" : ""}">${statusLabel(order.status)}</span></div>
    <div class="table-wrap"><table class="snapshot-table"><thead><tr><th>商品快照</th><th>模式</th><th>数量</th><th>单价</th><th>小计</th></tr></thead><tbody>${itemRows}</tbody></table></div>
    <div class="snapshot-total"><span>订单金额</span><strong>${formatMoney(order.totalAmount)}</strong></div>
    <dl class="detail-list"><dt>最近更新</dt><dd>${formatDate(order.updatedAt)}</dd><dt>当前状态</dt><dd>${statusLabel(order.status)}（不代表已付款）</dd></dl>`;
  $("cancel-order-button").hidden = order.status !== "RESERVED";
}

function addOrderLine(productId = "", quantity = 1) {
  const id = ++state.orderLineSequence;
  const row = document.createElement("div");
  row.className = "order-line";
  row.dataset.lineId = String(id);
  row.innerHTML = `<label class="order-line-product"><span class="sr-only">商品编号</span><input data-role="product-id" list="order-product-suggestions" maxlength="64" pattern="[A-Za-z0-9._-]+" placeholder="输入或选择商品编号" value="${escapeHtml(productId)}" required></label>
    <label class="order-line-quantity"><span class="sr-only">购买数量</span><input data-role="quantity" type="number" min="1" max="100000" step="1" value="${escapeHtml(quantity)}" required></label>
    <div class="order-line-price"><span data-role="summary">先查询商品</span><small data-role="mode">模式和单价来自当前商品</small></div>
    <button class="remove-line" type="button" data-role="remove" aria-label="移除此商品">×</button>`;
  $("order-lines").append(row);
  if (productId) loadOrderProductForLine(row, productId);
  updateOrderTotal();
}

async function loadOrderProductForLine(row, productId) {
  const summary = row.querySelector('[data-role="summary"]');
  const mode = row.querySelector('[data-role="mode"]');
  if (!PRODUCT_ID_PATTERN.test(productId)) {
    summary.textContent = "请输入有效商品编号";
    mode.textContent = "";
    row.dataset.loadedProductId = "";
    state.productDetails?.delete(productId);
    updateOrderTotal();
    return;
  }
  const cached = state.productCache.get(productId);
  if (cached && cached.active) {
    state.productDetails ??= new Map();
    state.productDetails.set(productId, cached);
    row.dataset.loadedProductId = productId;
    summary.textContent = `${cached.name} · ${formatMoney(cached.price)}`;
    mode.textContent = saleModeLabel(cached.saleMode);
    updateOrderTotal();
    return;
  }
  summary.textContent = "正在查询商品…";
  mode.textContent = "";
  try {
    const product = await api.product(productId);
    if (row.querySelector('[data-role="product-id"]').value.trim() !== productId) return;
    if (!product.active) {
      row.dataset.loadedProductId = "";
      summary.textContent = "商品已停用";
      mode.textContent = "无法创建新订单";
      state.productDetails?.delete(productId);
    } else {
      state.productCache.set(productId, product);
      state.productDetails ??= new Map();
      state.productDetails.set(productId, product);
      row.dataset.loadedProductId = productId;
      summary.textContent = `${product.name} · ${formatMoney(product.price)}`;
      mode.textContent = saleModeLabel(product.saleMode);
    }
  } catch (error) {
    row.dataset.loadedProductId = "";
    summary.textContent = explainError(error, "查询商品");
    mode.textContent = "";
  }
  updateOrderTotal();
}

function updateOrderTotal() {
  let totalCents = 0n;
  for (const row of $("order-lines").querySelectorAll(".order-line")) {
    const productId = row.querySelector('[data-role="product-id"]').value.trim();
    const quantity = Number(row.querySelector('[data-role="quantity"]').value);
    const product = state.productDetails?.get(productId);
    if (!product || row.dataset.loadedProductId !== productId || !Number.isInteger(quantity) || quantity < 1 || quantity > 100000) continue;
    const [whole = "0", fraction = ""] = String(product.price).split(".");
    const unitCents = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, "0").slice(0, 2));
    totalCents += unitCents * BigInt(quantity);
  }
  $("order-total-value").textContent = formatMoney(Number(totalCents) / 100);
}

function createIdempotencyKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  const bytes = new Uint8Array(16);
  if (globalThis.crypto?.getRandomValues) globalThis.crypto.getRandomValues(bytes);
  else for (let index = 0; index < bytes.length; index += 1) bytes[index] = Math.floor(Math.random() * 256);
  return `ui-${[...bytes].map((value) => value.toString(16).padStart(2, "0")).join("")}`;
}

function orderRequestFromForm() {
  const rows = [...$("order-lines").querySelectorAll(".order-line")];
  if (rows.length < 1 || rows.length > 50) throw new Error("一个订单需要 1 到 50 种商品。");
  const items = rows.map((row) => {
    const productId = row.querySelector('[data-role="product-id"]').value.trim();
    const quantity = Number(row.querySelector('[data-role="quantity"]').value);
    if (!PRODUCT_ID_PATTERN.test(productId)) throw new Error("请填写有效的商品编号。");
    if (!Number.isInteger(quantity) || quantity < 1 || quantity > 100000) throw new Error("商品数量必须是 1 到 100000 的整数。");
    if (row.dataset.loadedProductId !== productId) throw new Error(`请先确认商品 ${productId} 存在且处于启用状态。`);
    return { productId, quantity };
  });
  const productIds = new Set(items.map((item) => item.productId));
  if (productIds.size !== items.length) throw new Error("同一商品在订单中只能添加一次，请合并数量。");
  return { items };
}

async function openOrderDialog() {
  await loadActiveCatalog();
  state.productDetails = new Map();
  $("order-lines").replaceChildren();
  clearDialogFeedback($("order-dialog-feedback"));
  state.pendingOrderIntent = null;
  addOrderLine();
  $("order-dialog").showModal();
}

async function submitOrder(event) {
  event.preventDefault();
  const button = $("submit-order-button");
  if (button.disabled) return;
  clearDialogFeedback($("order-dialog-feedback"));
  let payload;
  try {
    payload = orderRequestFromForm();
  } catch (error) {
    setDialogFeedback($("order-dialog-feedback"), error.message);
    return;
  }
  const bodyFingerprint = JSON.stringify(payload);
  if (!state.pendingOrderIntent || state.pendingOrderIntent.bodyFingerprint !== bodyFingerprint) {
    state.pendingOrderIntent = { bodyFingerprint, key: createIdempotencyKey() };
  }
  setBusy(button, true, "正在创建…");
  try {
    const order = await api.createOrder(payload, state.pendingOrderIntent.key);
    state.pendingOrderIntent = null;
    $("order-dialog").close();
    $("order-status").value = "";
    state.orders.status = "";
    state.orders.page = 0;
    showToast(`订单已创建并预留库存：${order.orderId.slice(0, 8)}。订单尚未付款。`);
    await Promise.all([loadOrders(), loadProducts(), loadActiveCatalog()]);
  } catch (error) {
    const message = explainError(error, "创建订单");
    const retryHint = error instanceof ApiError && error.status === 409
      ? " 订单创建失败时所有商品预留会一起回滚；修正库存或数量后，保持请求内容不变重试会复用该幂等键。"
      : " 如果这是网络中断，请保持商品和数量不变后重试，避免创建第二个订单。";
    setDialogFeedback($("order-dialog-feedback"), `${message}${retryHint}`);
  } finally {
    setBusy(button, false);
  }
}

async function cancelActiveOrder() {
  const order = state.activeOrder;
  if (!order || order.status !== "RESERVED") return;
  if (!window.confirm(`确认取消订单 ${order.orderId}？系统会释放其中所有商品的库存预留。`)) return;
  const button = $("cancel-order-button");
  setBusy(button, true, "正在释放预留…");
  try {
    const cancelled = await api.cancelOrder(order.orderId);
    state.activeOrder = cancelled;
    renderOrderDetail(cancelled);
    const affectedIds = [...new Set(cancelled.items.map((item) => item.productId))];
    const snapshots = await Promise.all(affectedIds.map(async (productId) => [productId, await api.inventory(productId)]));
    snapshots.forEach(([productId, inventory]) => state.inventorySnapshots.set(productId, inventory));
    await Promise.all([loadOrders(), loadProducts(), loadActiveCatalog()]);
    if (state.inventoryProductId && affectedIds.includes(state.inventoryProductId)) {
      await loadInventory(state.inventoryProductId);
    }
    showToast("订单已取消，相关库存预留已释放，库存数据已刷新。");
  } catch (error) {
    showToast(explainError(error, "取消订单"), "error");
  } finally {
    setBusy(button, false);
  }
}

function wireEvents() {
  document.querySelectorAll(".nav-item").forEach((button) => button.addEventListener("click", () => switchView(button.dataset.view)));
  $("product-search-form").addEventListener("submit", (event) => {
    event.preventDefault();
    state.products.q = $("product-query").value.trim();
    state.products.saleMode = $("product-sale-filter").value;
    state.products.active = $("product-active").value;
    state.products.page = 0;
    loadProducts();
  });
  $("products-prev").addEventListener("click", () => { state.products.page = Math.max(0, state.products.page - 1); loadProducts(); });
  $("products-next").addEventListener("click", () => { if (state.products.page + 1 < state.products.totalPages) { state.products.page += 1; loadProducts(); } });
  $("create-product-button").addEventListener("click", () => openProductDialog());
  $("product-sale-mode").addEventListener("change", updateSaleModeFields);
  $("product-form").addEventListener("submit", submitProduct);
  $("products-table-body").addEventListener("click", (event) => {
    const button = event.target.closest("[data-product-action]");
    if (!button) return;
    const { productAction, productId } = button.dataset;
    if (productAction === "details") viewProduct(productId);
    if (productAction === "edit") openProductDialog(state.productCache.get(productId));
    if (productAction === "inventory") {
      switchView("inventory");
      loadInventory(productId);
    }
    if (productAction === "deactivate") deactivateProduct(productId);
  });
  $("detail-edit-button").addEventListener("click", () => {
    const product = state.activeProduct;
    if (!product) return;
    $("product-detail-dialog").close();
    openProductDialog(product);
  });
  $("inventory-search-form").addEventListener("submit", (event) => {
    event.preventDefault();
    loadInventory($("inventory-product-id").value.trim());
  });
  $("stock-adjustment-form").addEventListener("submit", submitStockAdjustment);
  $("stock-reason").addEventListener("input", (event) => { $("reason-length").textContent = String(event.target.value.length); });
  $("movements-prev").addEventListener("click", () => { state.movements.page = Math.max(0, state.movements.page - 1); loadMovements(); });
  $("movements-next").addEventListener("click", () => { if (state.movements.page + 1 < state.movements.totalPages) { state.movements.page += 1; loadMovements(); } });
  $("order-search-form").addEventListener("submit", (event) => {
    event.preventDefault();
    state.orders.status = $("order-status").value;
    state.orders.page = 0;
    loadOrders();
  });
  $("orders-prev").addEventListener("click", () => { state.orders.page = Math.max(0, state.orders.page - 1); loadOrders(); });
  $("orders-next").addEventListener("click", () => { if (state.orders.page + 1 < state.orders.totalPages) { state.orders.page += 1; loadOrders(); } });
  $("create-order-button").addEventListener("click", openOrderDialog);
  $("orders-table-body").addEventListener("click", (event) => {
    const button = event.target.closest("[data-order-action=details]");
    if (button) openOrderDetail(button.dataset.orderId);
  });
  $("order-lines").addEventListener("change", (event) => {
    const row = event.target.closest(".order-line");
    if (!row) return;
    if (event.target.dataset.role === "product-id") loadOrderProductForLine(row, event.target.value.trim());
    else updateOrderTotal();
  });
  $("order-lines").addEventListener("input", (event) => {
    if (event.target.dataset.role === "quantity") updateOrderTotal();
  });
  $("order-lines").addEventListener("click", (event) => {
    const remove = event.target.closest('[data-role="remove"]');
    if (!remove) return;
    const rows = $("order-lines").querySelectorAll(".order-line");
    if (rows.length <= 1) {
      showToast("订单至少需要一种商品。", "warning");
      return;
    }
    remove.closest(".order-line").remove();
    updateOrderTotal();
  });
  $("add-order-line").addEventListener("click", () => addOrderLine());
  $("order-form").addEventListener("submit", submitOrder);
  $("cancel-order-button").addEventListener("click", cancelActiveOrder);
  document.querySelectorAll("[data-close-dialog]").forEach((button) => button.addEventListener("click", () => $(button.dataset.closeDialog).close()));
  window.addEventListener("hashchange", () => switchView(window.location.hash.slice(1), false));
}

function initialize() {
  wireEvents();
  const initialView = window.location.hash.slice(1) || "products";
  switchView(initialView);
  checkHealth();
  loadActiveCatalog();
  window.setInterval(checkHealth, 60000);
}

initialize();
