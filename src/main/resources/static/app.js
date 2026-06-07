const uploadForm = document.querySelector("#uploadForm");
const uploadStatus = document.querySelector("#uploadStatus");
const documentIdInput = document.querySelector("#documentIdInput");
const updateTarget = document.querySelector("#updateTarget");
const updateTargetText = document.querySelector("#updateTargetText");
const clearUpdateTargetButton = document.querySelector("#clearUpdateTargetButton");
const documentList = document.querySelector("#documentList");
const refreshDocumentsButton = document.querySelector("#refreshDocumentsButton");
const chatForm = document.querySelector("#chatForm");
const questionInput = document.querySelector("#questionInput");
const messageList = document.querySelector("#messageList");
const sendButton = document.querySelector("#sendButton");
const stopButton = document.querySelector("#stopButton");
const clearButton = document.querySelector("#clearButton");
const conversationInput = document.querySelector("#conversationInput");
const projectInput = document.querySelector("#projectInput");
const topKInput = document.querySelector("#topKInput");
const docTypeInput = document.querySelector("#docTypeInput");
const tagsInput = document.querySelector("#tagsInput");
const tagEntryInput = document.querySelector("#tagEntryInput");
const tagChipList = document.querySelector("#tagChipList");
const tagOptionList = document.querySelector("#tagOptionList");
const docTypeMenu = document.querySelector("#docTypeMenu");
const projectMenu = document.querySelector("#projectMenu");
const tagMenu = document.querySelector("#tagMenu");
const tagPickerButton = document.querySelector("#tagPickerButton");
const tagPickerSummary = document.querySelector("#tagPickerSummary");
const tagCreateButton = document.querySelector("#tagCreateButton");
const docTypeToggle = document.querySelector("#docTypeToggle");
const projectToggle = document.querySelector("#projectToggle");
const docTypeClear = document.querySelector("#docTypeClear");
const projectClear = document.querySelector("#projectClear");

let activeController = null;
let uploadPollTimer = null;
let selectedTags = [];

const storage = {
    conversationId: "team-rag-conversation",
    project: "team-rag-project",
    topK: "team-rag-top-k",
    docTypes: "team-rag-doc-types",
    projects: "team-rag-projects",
    tags: "team-rag-tags"
};

const defaultOptions = {
    docTypes: ["研发规范", "项目资料", "培训材料", "问题排查"],
    projects: [],
    tags: ["规范", "培训", "排查"]
};

const optionState = {
    docTypes: [],
    projects: [],
    tags: []
};

const docTypeCombo = createSingleCombo({
    input: docTypeInput,
    menu: docTypeMenu,
    toggleButton: docTypeToggle,
    clearButton: docTypeClear,
    optionKey: "docTypes"
});
const projectCombo = createSingleCombo({
    input: projectInput,
    menu: projectMenu,
    toggleButton: projectToggle,
    clearButton: projectClear,
    optionKey: "projects"
});
const tagCombo = createTagCombo();

loadSettings();
loadOptionHistory();
projectInput.value = "";
syncTagsInput();
loadDocumentList();

uploadForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    tagCombo.commit();
    const formData = new FormData(uploadForm);
    const file = formData.get("file");
    if (!file || !file.name) {
        setUploadStatus("请选择要上传的资料文件。", "error");
        return;
    }

    setUploadStatus("正在提交入库任务...", "");
    uploadForm.querySelector("button[type='submit']").disabled = true;
    try {
        const response = await fetch("/api/document/upload", {
            method: "POST",
            body: formData
        });
        const payload = await readJsonOrText(response);
        if (!response.ok) {
            throw new Error(payload.message || payload || "上传失败");
        }
        setUploadStatus(formatUploadResponse(payload, file.name), payload.status === "ACTIVE" ? "ok" : "");
        rememberUploadOptions(formData);
        if (payload.jobId) {
            pollIngestionJob(payload.jobId);
        }
        clearUpdateTarget();
        loadDocumentList();
    } catch (error) {
        setUploadStatus(error.message, "error");
    } finally {
        uploadForm.querySelector("button[type='submit']").disabled = false;
    }
});

refreshDocumentsButton.addEventListener("click", loadDocumentList);

clearUpdateTargetButton.addEventListener("click", clearUpdateTarget);

chatForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const question = questionInput.value.trim();
    if (!question) {
        questionInput.focus();
        return;
    }

    saveSettings();
    addMessage("user", question);
    questionInput.value = "";
    const assistantContent = addMessage("assistant", "");
    await askQuestion(question, assistantContent);
});

stopButton.addEventListener("click", () => {
    if (activeController) {
        activeController.abort();
    }
});

clearButton.addEventListener("click", () => {
    messageList.innerHTML = "";
    addMessage("assistant", "会话已清空。");
});

[conversationInput, projectInput, topKInput].forEach((input) => {
    input.addEventListener("change", saveSettings);
});

document.addEventListener("pointerdown", (event) => {
    if (!event.target.closest("[data-combo]")) {
        docTypeCombo.close();
        projectCombo.close();
        tagCombo.close();
    }
});

questionInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
        chatForm.requestSubmit();
    }
});

async function askQuestion(question, assistantContent) {
    activeController = new AbortController();
    setChatBusy(true);
    const conversationValue = conversationInput.value.trim();
    const requestBody = {
        query: question,
        topK: Number(topKInput.value || 6)
    };
    if (!conversationValue || /^\d+$/.test(conversationValue)) {
        requestBody.memoryId = Number(conversationValue || 1);
    } else {
        requestBody.conversationId = conversationValue;
    }
    if (projectInput.value.trim()) {
        requestBody.project = projectInput.value.trim();
    }

    try {
        const response = await fetch("/api/rag/qa", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify(requestBody),
            signal: activeController.signal
        });

        if (!response.ok) {
            const payload = await readJsonOrText(response);
            throw new Error(payload.message || payload || "问答请求失败");
        }

        await readStream(response, (text) => {
            assistantContent.textContent += text;
            scrollMessagesToBottom();
        });

        if (!assistantContent.textContent.trim()) {
            assistantContent.textContent = "没有收到模型输出。";
        }
    } catch (error) {
        if (error.name === "AbortError") {
            assistantContent.textContent += "\n\n已停止生成。";
        } else {
            assistantContent.classList.add("error");
            assistantContent.textContent = error.message;
        }
    } finally {
        activeController = null;
        setChatBusy(false);
        scrollMessagesToBottom();
    }
}

async function readStream(response, onText) {
    const reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";

    while (true) {
        const { value, done } = await reader.read();
        if (done) {
            break;
        }
        buffer += decoder.decode(value, { stream: true });
        buffer = consumeEventBuffer(buffer, onText);
    }

    buffer += decoder.decode();
    consumeEventBuffer(buffer, onText, true);
}

function consumeEventBuffer(buffer, onText, flush = false) {
    const separator = /\r?\n\r?\n/;
    while (separator.test(buffer)) {
        const parts = buffer.split(separator);
        const eventBlock = parts.shift();
        buffer = parts.join("\n\n");
        emitEventBlock(eventBlock, onText);
    }
    if (flush && buffer.trim()) {
        emitEventBlock(buffer, onText);
        return "";
    }
    return buffer;
}

function emitEventBlock(eventBlock, onText) {
    const dataLines = eventBlock
        .split(/\r?\n/)
        .map((line) => line.trimEnd())
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).trimStart());

    if (dataLines.length > 0) {
        const text = dataLines.join("\n");
        if (text !== "[DONE]") {
            onText(text);
        }
        return;
    }

    const raw = eventBlock.trim();
    if (raw && raw !== "[DONE]") {
        onText(raw);
    }
}

function addMessage(role, text) {
    const article = document.createElement("article");
    article.className = `message ${role}`;

    const avatar = document.createElement("div");
    avatar.className = "message-avatar";
    avatar.textContent = role === "user" ? "你" : "AI";

    const content = document.createElement("div");
    content.className = "message-content";
    content.textContent = text;

    if (role === "user") {
        article.append(content, avatar);
    } else {
        article.append(avatar, content);
    }
    messageList.append(article);
    scrollMessagesToBottom();
    return content;
}

function setUploadStatus(text, state) {
    uploadStatus.textContent = text;
    uploadStatus.className = `status-box${state ? ` ${state}` : ""}`;
}

async function pollIngestionJob(jobId) {
    clearTimeout(uploadPollTimer);
    try {
        const response = await fetch(`/api/document/jobs/${encodeURIComponent(jobId)}`);
        const payload = await readJsonOrText(response);
        if (!response.ok) {
            throw new Error(payload.message || payload || "查询入库任务失败");
        }
        setUploadStatus(formatJobStatus(payload), payload.status === "SUCCESS" ? "ok" : payload.status === "FAILED" ? "error" : "");
        await loadDocumentList();
        if (payload.status === "PENDING" || payload.status === "RUNNING") {
            uploadPollTimer = setTimeout(() => pollIngestionJob(jobId), 1800);
        }
    } catch (error) {
        setUploadStatus(error.message, "error");
    }
}

async function loadDocumentList() {
    try {
        const response = await fetch("/api/document");
        const payload = await readJsonOrText(response);
        if (!response.ok) {
            throw new Error(payload.message || payload || "查询文档状态失败");
        }
        renderDocumentList(Array.isArray(payload) ? payload : []);
    } catch (error) {
        documentList.innerHTML = "";
        const item = document.createElement("div");
        item.className = "empty-state error";
        item.textContent = error.message;
        documentList.append(item);
    }
}

function renderDocumentList(documents) {
    documentList.innerHTML = "";
    if (documents.length === 0) {
        const empty = document.createElement("div");
        empty.className = "empty-state";
        empty.textContent = "暂无入库文档";
        documentList.append(empty);
        return;
    }
    documents.slice(0, 8).forEach((item) => {
        const card = document.createElement("article");
        card.className = "document-item";

        const titleRow = document.createElement("div");
        titleRow.className = "document-title-row";
        const title = document.createElement("strong");
        title.textContent = item.filename || "未命名文档";
        const status = document.createElement("span");
        status.className = `status-pill ${statusClass(item.status)}`;
        status.textContent = statusText(item.status);
        titleRow.append(title, status);

        const meta = document.createElement("div");
        meta.className = "document-meta";
        meta.textContent = [
            item.docType || "未分类",
            item.project || "未指定项目",
            item.currentVersion ? `v${item.currentVersion}` : "处理中"
        ].join(" · ");

        const action = document.createElement("button");
        action.type = "button";
        action.className = "text-button";
        action.textContent = "上传新版本";
        action.addEventListener("click", () => setUpdateTarget(item));

        card.append(titleRow, meta, action);
        documentList.append(card);
    });
}

function setUpdateTarget(item) {
    documentIdInput.value = item.documentId || "";
    updateTargetText.textContent = `将为「${item.filename || item.documentId}」上传新版本`;
    updateTarget.hidden = false;
}

function clearUpdateTarget() {
    documentIdInput.value = "";
    updateTargetText.textContent = "";
    updateTarget.hidden = true;
}

function formatUploadResponse(payload, fallbackFilename) {
    return [
        payload.message || "文档已提交入库任务",
        `文档：${payload.filename || fallbackFilename}`,
        `文档 ID：${payload.documentId || "-"}`,
        `版本：${payload.version || "-"}`,
        `状态：${statusText(payload.status)}`
    ].join("\n");
}

function formatJobStatus(payload) {
    const lines = [
        `入库任务：${statusText(payload.status)}`,
        `文档 ID：${payload.documentId || "-"}`,
        `版本：${payload.version || "-"}`,
        `进度：${payload.progress ?? 0}%`
    ];
    if (payload.errorMessage) {
        lines.push(`失败原因：${payload.errorMessage}`);
    }
    return lines.join("\n");
}

function statusText(status) {
    const labels = {
        PENDING: "等待处理",
        RUNNING: "处理中",
        PROCESSING: "处理中",
        SUCCESS: "已入库",
        ACTIVE: "已入库",
        SUPERSEDED: "已被替代",
        FAILED: "失败",
        DELETED: "已删除"
    };
    return labels[status] || status || "-";
}

function statusClass(status) {
    if (status === "ACTIVE" || status === "SUCCESS") {
        return "success";
    }
    if (status === "FAILED") {
        return "failed";
    }
    if (status === "PROCESSING" || status === "PENDING" || status === "RUNNING") {
        return "running";
    }
    return "";
}

function setChatBusy(isBusy) {
    sendButton.disabled = isBusy;
    stopButton.disabled = !isBusy;
    questionInput.disabled = isBusy;
}

async function readJsonOrText(response) {
    const contentType = response.headers.get("content-type") || "";
    if (contentType.includes("application/json")) {
        return response.json();
    }
    return response.text();
}

function scrollMessagesToBottom() {
    messageList.scrollTop = messageList.scrollHeight;
}

function loadSettings() {
    conversationInput.value = localStorage.getItem(storage.conversationId) || conversationInput.value;
    topKInput.value = localStorage.getItem(storage.topK) || topKInput.value;
}

function saveSettings() {
    localStorage.setItem(storage.conversationId, conversationInput.value.trim() || "1");
    localStorage.setItem(storage.project, projectInput.value.trim());
    localStorage.setItem(storage.topK, topKInput.value || "6");
}

function loadOptionHistory() {
    optionState.docTypes = mergeValues(readList(storage.docTypes), defaultOptions.docTypes);
    optionState.projects = mergeValues(readList(storage.projects), defaultOptions.projects);
    optionState.tags = mergeValues(readList(storage.tags), defaultOptions.tags);
    docTypeCombo.render();
    projectCombo.render();
    tagCombo.render();
}

function rememberUploadOptions(formData) {
    const docType = textValue(formData.get("docType"));
    const project = textValue(formData.get("project"));
    const tags = splitTags(textValue(formData.get("tags")));

    if (docType) {
        addHistoryValue(storage.docTypes, docType);
    }
    if (project) {
        addHistoryValue(storage.projects, project);
    }
    for (const tag of tags) {
        addHistoryValue(storage.tags, tag);
    }
    loadOptionHistory();
}

function addHistoryValue(key, value) {
    const values = mergeValues(readList(key), [value]).slice(0, 40);
    localStorage.setItem(key, JSON.stringify(values));
}

function readList(key) {
    try {
        const parsed = JSON.parse(localStorage.getItem(key) || "[]");
        return Array.isArray(parsed) ? parsed.map(textValue).filter(Boolean) : [];
    } catch {
        return [];
    }
}

function mergeValues(left, right) {
    const seen = new Set();
    const result = [];
    for (const value of [...left, ...right]) {
        const normalized = textValue(value);
        const key = normalized.toLowerCase();
        if (normalized && !seen.has(key)) {
            seen.add(key);
            result.push(normalized);
        }
    }
    return result;
}

function splitTags(value) {
    return value
        .split(/[,，]/)
        .map(textValue)
        .filter(Boolean);
}

function textValue(value) {
    return String(value || "").trim();
}

function createSingleCombo({ input, menu, toggleButton, clearButton, optionKey }) {
    let activeIndex = -1;
    const root = input.closest(".combo");

    input.addEventListener("focus", open);
    input.addEventListener("input", () => {
        activeIndex = -1;
        updateValueState();
        open();
    });
    input.addEventListener("keydown", (event) => {
        const options = menuOptions();
        if (event.key === "ArrowDown") {
            event.preventDefault();
            open();
            activeIndex = options.length === 0 ? -1 : Math.min(activeIndex + 1, options.length - 1);
            render();
            return;
        }
        if (event.key === "ArrowUp") {
            event.preventDefault();
            open();
            activeIndex = options.length === 0 ? -1 : Math.max(activeIndex - 1, 0);
            render();
            return;
        }
        if (event.key === "Enter" && activeIndex >= 0 && options[activeIndex]) {
            event.preventDefault();
            select(options[activeIndex].value);
            return;
        }
        if (event.key === "Escape") {
            close();
        }
    });
    toggleButton.addEventListener("pointerdown", preventBlur);
    toggleButton.addEventListener("click", () => {
        if (menu.classList.contains("open")) {
            close();
        } else {
            input.focus();
            open(true);
        }
    });
    clearButton.addEventListener("pointerdown", preventBlur);
    clearButton.addEventListener("click", () => {
        input.value = "";
        activeIndex = -1;
        updateValueState();
        input.focus();
        open(true);
        saveSettings();
    });

    function open(showAll = false) {
        if (showAll) {
            activeIndex = -1;
        }
        render(showAll);
        menu.classList.add("open");
        input.setAttribute("aria-expanded", "true");
    }

    function close() {
        menu.classList.remove("open");
        input.setAttribute("aria-expanded", "false");
        activeIndex = -1;
    }

    function render(showAll = false) {
        const options = menuOptions(showAll);
        menu.innerHTML = "";
        if (options.length === 0) {
            const empty = document.createElement("div");
            empty.className = "combo-empty";
            empty.textContent = "暂无可选项，可直接输入新值";
            menu.append(empty);
            return;
        }
        options.forEach((option, index) => {
            const button = document.createElement("button");
            button.type = "button";
            button.className = `combo-option${option.kind === "create" ? " create" : ""}${index === activeIndex ? " active" : ""}`;
            button.role = "option";
            const mainText = document.createElement("span");
            mainText.className = "combo-option-main";
            mainText.textContent = option.kind === "create" ? `新建「${option.value}」` : option.value;
            const metaText = document.createElement("span");
            metaText.className = "combo-option-meta";
            metaText.textContent = option.kind === "create" ? "Enter" : "已有";
            button.append(mainText, metaText);
            button.addEventListener("pointerdown", preventBlur);
            button.addEventListener("click", () => select(option.value));
            menu.append(button);
        });
    }

    function select(value) {
        input.value = value;
        updateValueState();
        close();
        input.focus();
        saveSettings();
    }

    function menuOptions(showAll = false) {
        const options = filteredOptions(showAll).map((value) => ({ kind: "existing", value }));
        const query = input.value.trim();
        const exists = optionState[optionKey].some((option) => option.toLowerCase() === query.toLowerCase());
        if (query && !exists) {
            options.unshift({ kind: "create", value: query });
        }
        return options;
    }

    function filteredOptions(showAll = false) {
        const query = showAll ? "" : input.value.trim().toLowerCase();
        return optionState[optionKey].filter((option) => option.toLowerCase().includes(query));
    }

    function updateValueState() {
        root.classList.toggle("has-value", Boolean(input.value.trim()));
    }

    updateValueState();
    return { close, render };
}

function createTagCombo() {
    tagPickerButton.addEventListener("click", () => {
        if (tagMenu.classList.contains("open")) {
            close();
            return;
        }
        open();
    });
    tagEntryInput.addEventListener("input", () => {
        consumeDelimitedTags();
        render();
    });
    tagEntryInput.addEventListener("keydown", (event) => {
        if (event.key === "Enter") {
            event.preventDefault();
            commit();
            return;
        }
        if (event.key === "Escape") {
            close();
        }
    });
    tagCreateButton.addEventListener("pointerdown", preventBlur);
    tagCreateButton.addEventListener("click", () => {
        commit();
        tagEntryInput.focus();
    });

    function open() {
        render();
        tagMenu.classList.add("open");
        tagPickerButton.setAttribute("aria-expanded", "true");
        setTimeout(() => tagEntryInput.focus(), 0);
    }

    function close() {
        tagMenu.classList.remove("open");
        tagPickerButton.setAttribute("aria-expanded", "false");
    }

    function render() {
        renderChips();
        renderOptions();
        renderSummary();
        syncTagsInput();
    }

    function renderOptions() {
        const options = filteredTagOptions();
        tagOptionList.innerHTML = "";
        if (options.length === 0) {
            const empty = document.createElement("div");
            empty.className = "tag-option-empty";
            empty.textContent = optionState.tags.length === selectedTags.length ? "已有标签已全部选中" : "暂无匹配标签";
            tagOptionList.append(empty);
            return;
        }
        options.forEach((option) => {
            const button = document.createElement("button");
            button.type = "button";
            button.className = "tag-option";
            button.textContent = option;
            button.addEventListener("pointerdown", preventBlur);
            button.addEventListener("click", () => addTag(option));
            tagOptionList.append(button);
        });
    }

    function addTag(value) {
        const tag = textValue(value);
        if (!tag) {
            return;
        }
        const exists = selectedTags.some((item) => item.toLowerCase() === tag.toLowerCase());
        if (!exists) {
            selectedTags.push(tag);
        }
        tagEntryInput.value = "";
        render();
        tagEntryInput.focus();
    }

    function commit() {
        splitTags(tagEntryInput.value).forEach(addTag);
        tagEntryInput.value = "";
        render();
    }

    function consumeDelimitedTags() {
        const value = tagEntryInput.value;
        if (!/[,，]/.test(value)) {
            return;
        }
        const parts = value.split(/[,，]/);
        const rest = parts.pop() || "";
        parts.map(textValue).filter(Boolean).forEach(addTag);
        tagEntryInput.value = rest;
    }

    function renderChips() {
        tagChipList.querySelectorAll(".tag-chip").forEach((chip) => chip.remove());
        tagChipList.querySelectorAll(".tag-empty-hint").forEach((hint) => hint.remove());
        if (selectedTags.length === 0) {
            const hint = document.createElement("span");
            hint.className = "tag-empty-hint";
            hint.textContent = "还没有选择标签";
            tagChipList.append(hint);
            return;
        }
        selectedTags.forEach((tag) => {
            const chip = document.createElement("span");
            chip.className = "tag-chip";
            chip.textContent = tag;
            const remove = document.createElement("button");
            remove.type = "button";
            remove.setAttribute("aria-label", `移除${tag}`);
            remove.addEventListener("click", () => {
                selectedTags = selectedTags.filter((item) => item !== tag);
                renderChips();
                render();
            });
            chip.append(remove);
            tagChipList.append(chip);
        });
    }

    function renderSummary() {
        if (selectedTags.length === 0) {
            tagPickerSummary.textContent = "选择或新建标签";
            tagPickerButton.classList.remove("has-tags");
            return;
        }
        tagPickerSummary.textContent = selectedTags.length <= 2
            ? selectedTags.join("、")
            : `${selectedTags.slice(0, 2).join("、")} 等 ${selectedTags.length} 个标签`;
        tagPickerButton.classList.add("has-tags");
    }

    function filteredTagOptions() {
        const query = tagEntryInput.value.trim().toLowerCase();
        return optionState.tags
            .filter((option) => !selectedTags.some((tag) => tag.toLowerCase() === option.toLowerCase()))
            .filter((option) => option.toLowerCase().includes(query));
    }

    render();
    return { close, commit, render };
}

function syncTagsInput() {
    tagsInput.value = selectedTags.join(",");
}

function preventBlur(event) {
    event.preventDefault();
}
