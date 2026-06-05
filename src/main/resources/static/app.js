const uploadForm = document.querySelector("#uploadForm");
const uploadStatus = document.querySelector("#uploadStatus");
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
const docTypeMenu = document.querySelector("#docTypeMenu");
const projectMenu = document.querySelector("#projectMenu");
const tagMenu = document.querySelector("#tagMenu");
const docTypeToggle = document.querySelector("#docTypeToggle");
const projectToggle = document.querySelector("#projectToggle");
const tagToggle = document.querySelector("#tagToggle");
const docTypeClear = document.querySelector("#docTypeClear");
const projectClear = document.querySelector("#projectClear");
const tagClear = document.querySelector("#tagClear");

let activeController = null;
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

uploadForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    tagCombo.commit();
    const formData = new FormData(uploadForm);
    const file = formData.get("file");
    if (!file || !file.name) {
        setUploadStatus("请选择要上传的资料文件。", "error");
        return;
    }

    setUploadStatus("正在解析并写入 Pinecone...", "");
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
        setUploadStatus(
            `已入库：${payload.filename || file.name}\n文档 ID：${payload.documentId || "-"}\n片段数：${payload.segmentCount ?? "-"}`,
            "ok"
        );
        rememberUploadOptions(formData);
    } catch (error) {
        setUploadStatus(error.message, "error");
    } finally {
        uploadForm.querySelector("button[type='submit']").disabled = false;
    }
});

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

    input.addEventListener("focus", open);
    input.addEventListener("input", () => {
        activeIndex = -1;
        open();
    });
    input.addEventListener("keydown", (event) => {
        const options = filteredOptions();
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
            select(options[activeIndex]);
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
        const options = filteredOptions(showAll);
        menu.innerHTML = "";
        if (options.length === 0) {
            const empty = document.createElement("div");
            empty.className = "combo-empty";
            empty.textContent = "无匹配项";
            menu.append(empty);
            return;
        }
        options.forEach((option, index) => {
            const button = document.createElement("button");
            button.type = "button";
            button.className = `combo-option${index === activeIndex ? " active" : ""}`;
            button.role = "option";
            button.textContent = option;
            button.addEventListener("pointerdown", preventBlur);
            button.addEventListener("click", () => select(option));
            menu.append(button);
        });
    }

    function select(value) {
        input.value = value;
        close();
        input.focus();
        saveSettings();
    }

    function filteredOptions(showAll = false) {
        const query = showAll ? "" : input.value.trim().toLowerCase();
        return optionState[optionKey].filter((option) => option.toLowerCase().includes(query));
    }

    return { close, render };
}

function createTagCombo() {
    let activeIndex = -1;

    tagEntryInput.addEventListener("focus", open);
    tagEntryInput.addEventListener("input", () => {
        consumeDelimitedTags();
        activeIndex = -1;
        open();
    });
    tagEntryInput.addEventListener("keydown", (event) => {
        const options = filteredTagOptions();
        if (event.key === "Enter" || event.key === "Tab") {
            if (activeIndex >= 0 && options[activeIndex]) {
                event.preventDefault();
                addTag(options[activeIndex]);
            } else if (tagEntryInput.value.trim()) {
                event.preventDefault();
                commit();
            }
            return;
        }
        if (event.key === "Backspace" && !tagEntryInput.value && selectedTags.length > 0) {
            selectedTags.pop();
            renderChips();
            render();
            return;
        }
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
        if (event.key === "Escape") {
            close();
        }
    });
    tagToggle.addEventListener("pointerdown", preventBlur);
    tagToggle.addEventListener("click", () => {
        if (tagMenu.classList.contains("open")) {
            close();
        } else {
            tagEntryInput.focus();
            open(true);
        }
    });
    tagClear.addEventListener("pointerdown", preventBlur);
    tagClear.addEventListener("click", () => {
        selectedTags = [];
        tagEntryInput.value = "";
        renderChips();
        open(true);
        tagEntryInput.focus();
    });

    function open(showAll = false) {
        if (showAll) {
            activeIndex = -1;
        }
        render(showAll);
        tagMenu.classList.add("open");
        tagEntryInput.setAttribute("aria-expanded", "true");
    }

    function close() {
        tagMenu.classList.remove("open");
        tagEntryInput.setAttribute("aria-expanded", "false");
        activeIndex = -1;
    }

    function render(showAll = false) {
        const options = filteredTagOptions(showAll);
        tagMenu.innerHTML = "";
        if (options.length === 0) {
            const empty = document.createElement("div");
            empty.className = "combo-empty";
            empty.textContent = "无匹配项";
            tagMenu.append(empty);
            return;
        }
        options.forEach((option, index) => {
            const button = document.createElement("button");
            button.type = "button";
            button.className = `combo-option${index === activeIndex ? " active" : ""}`;
            button.role = "option";
            button.textContent = option;
            button.addEventListener("pointerdown", preventBlur);
            button.addEventListener("click", () => addTag(option));
            tagMenu.append(button);
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
        renderChips();
        render(true);
        tagEntryInput.focus();
    }

    function commit() {
        splitTags(tagEntryInput.value).forEach(addTag);
        tagEntryInput.value = "";
        renderChips();
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
        selectedTags.forEach((tag) => {
            const chip = document.createElement("span");
            chip.className = "tag-chip";
            chip.textContent = tag;
            const remove = document.createElement("button");
            remove.type = "button";
            remove.setAttribute("aria-label", `移除${tag}`);
            remove.textContent = "×";
            remove.addEventListener("click", () => {
                selectedTags = selectedTags.filter((item) => item !== tag);
                renderChips();
                render();
            });
            chip.append(remove);
            tagChipList.insertBefore(chip, tagEntryInput);
        });
        syncTagsInput();
    }

    function filteredTagOptions(showAll = false) {
        const query = showAll ? "" : tagEntryInput.value.trim().toLowerCase();
        return optionState.tags
            .filter((option) => !selectedTags.some((tag) => tag.toLowerCase() === option.toLowerCase()))
            .filter((option) => option.toLowerCase().includes(query));
    }

    return { close, commit, render };
}

function syncTagsInput() {
    tagsInput.value = selectedTags.join(",");
}

function preventBlur(event) {
    event.preventDefault();
}
