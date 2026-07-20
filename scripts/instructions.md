# SynapseMCP — How to Start and Use the App

This is a complete, beginner-friendly walkthrough for running SynapseMCP and talking to it. No prior
knowledge of the codebase is assumed — if you can copy-paste a command into a terminal, you can
follow this.

**What is SynapseMCP?** It's a multi-tenant Retrieval-Augmented Generation (RAG) platform. In plain
terms: you upload your own documents (PDFs, Word files, plain text, etc.), and the app lets you
*search* them and *ask questions* about them in natural language, with an AI model generating the
answer using only the content you uploaded (not the AI's general knowledge).

There are **two different ways to talk to the app** once it's running:

1. **The REST API** — plain HTTP endpoints under `/api/v1/...`. Use this if you're building your own
   script, website, or app on top of SynapseMCP.
2. **MCP (Model Context Protocol)** — a single endpoint at `/mcp` that AI assistants (like Claude
   Desktop, or any MCP-compatible client) can connect to and use as a set of "tools." Use this if you
   want an AI assistant to manage tenants/knowledge bases/documents on your behalf.

**Important: these are not two separate servers.** It's the same running application, the same
process, the same port. `/api/v1/...` and `/mcp` are just two different doors into the same house.

---

## Table of Contents

1. [Before you start](#1-before-you-start)
2. [Starting the application](#2-starting-the-application)
3. [Part A — Using the REST API with curl](#3-part-a--using-the-rest-api-with-curl)
4. [Part B — Using MCP with curl](#4-part-b--using-mcp-with-curl)
5. [Common errors and what they mean](#5-common-errors-and-what-they-mean)
6. [Quick reference](#6-quick-reference)
7. [Stopping the application](#7-stopping-the-application)

---

## 1. Before you start

You need the following already installed on your machine:

| Requirement | Why | Check it's installed |
|---|---|---|
| **Java 21 or newer** | Runs the application itself | `java -version` |
| **PostgreSQL 17+ with the `pgvector` extension** | Stores all your data, including the AI embeddings used for search | `psql --version` |
| **Redis** | Caching and rate limiting | `redis-cli --version` |
| **curl** | Every example in this guide uses it to talk to the app | `curl --version` (pre-installed on macOS/Linux) |
| **jq** | Formats/extracts values from the JSON responses in these examples | `jq --version` |

You also need an API key from an AI provider to actually generate answers and embeddings — for
example an [OpenAI](https://platform.openai.com/api-keys) key (`sk-...`). Without one, you can still
create tenants and knowledge bases, but uploading documents or asking questions will fail (the app
needs a real provider to turn your text into searchable vectors and to write answers).

Supported providers: **OpenAI**, **Anthropic** (chat only, no embeddings), **Google GenAI**, and
**Ollama** (self-hosted, no API key needed).

**Don't want to set any of this up by hand?** Skip to [2.1](#21-easiest-option-the-guided-setup-wizard) —
there's a wizard that does it all for you, including installing Postgres/Redis if missing.

---

## 2. Starting the application

### 2.1 Easiest option: the guided setup wizard

From the project's root folder, run:

```bash
# macOS / Linux:
./scripts/init-environment.sh

# Windows (PowerShell):
.\scripts\init-environment.ps1
```

This interactive wizard will:
1. Ask you a few questions (database connection details, which AI provider to use, etc. — every
   question has a sensible default, so you can just press Enter to accept it).
2. Check that Postgres, Redis, and Java are installed and reachable.
3. **Destructively reset** the app's databases and caches to a clean slate (it asks for confirmation
   first — nothing happens without you typing "y").
4. Start the application and wait for it to become healthy.
5. Create a test tenant, configure your AI provider, create a knowledge base, upload a small test
   document, and ask it a test question — proving your whole setup (database, cache, and AI
   provider) genuinely works end to end.
6. Stop the application and print a summary, including the API key it created for you.

This is the recommended way to get started the very first time, or any time you want to wipe
everything and start over.

### 2.2 Starting it yourself (once you already have Postgres/Redis running)

If you already have a database set up (e.g. because you ran the wizard once before) and just want
to start the app:

```bash
export DB_URL="jdbc:postgresql://localhost:5432"
export DB_USERNAME="your-postgres-username"
export DB_PASSWORD="your-postgres-password"   # leave empty ("") if your Postgres uses trust/peer auth
export REDIS_HOST="localhost"
export REDIS_PORT="6379"

./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

A few notes on that `DB_URL` value: it's the *base* URL only — no database name at the end. The app
appends the database name itself depending on which profile you're running.

The first time you start it this way against a brand-new Postgres server, the app creates its own
database, role, tables, and search indexes automatically — you don't need to run any SQL by hand.

### 2.3 Confirming it's running

Once started, check the health endpoint:

```bash
curl -s http://localhost:8080/actuator/health | jq .
```

Expected response:

```json
{
  "status": "UP"
}
```

The app listens on **port 8080** by default. Every example in this guide assumes that.

---

## 3. Part A — Using the REST API with curl

This section walks through the complete, natural flow: create a tenant → tell it which AI model to
use → create a knowledge base → upload a document → search/ask questions about it.

Every example below is copy-paste ready. Replace the placeholder values (like `sk-your-openai-key`)
with your own.

### 3.1 Create a tenant

A "tenant" is your own private account — its data is completely isolated from every other tenant's.
This is the *only* endpoint in the whole API that doesn't require a key, for the obvious reason that
you don't have one yet.

```bash
curl -s -X POST http://localhost:8080/api/v1/tenants \
  -H "Content-Type: application/json" \
  -d '{"name": "Acme Corp"}'
```

Response:

```json
{
  "tenantId": "d87e5fa0-c444-4492-9595-7c5779fd86e5",
  "name": "Acme Corp",
  "apiKey": "rKUeSPL1Ub_0qmUclildKhXiE6VSZdSrKNMcUb4bTs4"
}
```

**Save that `apiKey` somewhere safe right now.** It is shown to you exactly once — the app never
stores it in a way it can show you again. Every other request in this guide needs it, sent as an
`Authorization: Bearer <apiKey>` header.

To make the rest of this guide easier to follow, save your tenant id and API key into shell
variables:

```bash
export TENANT_ID="d87e5fa0-c444-4492-9595-7c5779fd86e5"
export API_KEY="rKUeSPL1Ub_0qmUclildKhXiE6VSZdSrKNMcUb4bTs4"
```

(Tip: you can capture these automatically instead of copy-pasting by hand:

```bash
TENANT_JSON=$(curl -s -X POST http://localhost:8080/api/v1/tenants \
  -H "Content-Type: application/json" -d '{"name": "Acme Corp"}')
export TENANT_ID=$(echo "$TENANT_JSON" | jq -r '.tenantId')
export API_KEY=$(echo "$TENANT_JSON" | jq -r '.apiKey')
```
)

**Heads up:** tenant creation is limited to **5 per hour per IP address**, as an anti-abuse measure
since it needs no login. You won't hit this in normal use.

### 3.2 Tell it which AI model to use

Before you can do anything else, you must configure which chat model (for answering questions) and
embedding model (for turning text into searchable vectors) your tenant uses, plus the API key(s) for
that provider.

```bash
curl -s -X PUT "http://localhost:8080/api/v1/tenants/$TENANT_ID/model-config" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $API_KEY" \
  -d '{
    "chatProvider": "openai",
    "chatModel": "gpt-4o",
    "embeddingProvider": "openai",
    "embeddingModel": "text-embedding-3-small",
    "chatApiKey": "sk-your-openai-key",
    "embeddingApiKey": "sk-your-openai-key"
  }'
```

Response:

```json
{
  "tenantId": "d87e5fa0-c444-4492-9595-7c5779fd86e5",
  "chatProvider": "openai",
  "chatModel": "gpt-4o",
  "embeddingProvider": "openai",
  "embeddingModel": "text-embedding-3-small",
  "createdAt": "2026-07-19T21:45:09.565715Z"
}
```

Notice the response never echoes your API keys back — they're never shown again once submitted
(same principle as your tenant API key).

**Important:** this step does *not* verify your API key is actually valid — it just saves what you
gave it. The very first *real* check happens in the next step (creating a knowledge base), which
makes one genuine call to your embedding provider. If your key is wrong, that's where you'll find
out.

You can fetch your current configuration back at any time (still without ever seeing the keys):

```bash
curl -s "http://localhost:8080/api/v1/tenants/$TENANT_ID/model-config" \
  -H "Authorization: Bearer $API_KEY"
```

If using **Ollama** (self-hosted, no cloud account), you can leave `chatApiKey`/`embeddingApiKey` out
entirely — no key is needed. Every other provider requires one.

### 3.3 Create a knowledge base

A "knowledge base" is a named bucket of documents — you can have several per tenant (up to 10), for
example one per project or department.

```bash
curl -s -X POST http://localhost:8080/api/v1/knowledgebase \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $API_KEY" \
  -d '{"name": "product-docs"}'
```

Response:

```json
{
  "id": "c1127044-05a6-4119-b0f7-5e9146672ce9",
  "name": "product-docs",
  "embeddingDim": 1536,
  "documentStatusSummary": {
    "pending": 0,
    "indexing": 0,
    "ready": 0,
    "failed": 0
  }
}
```

`embeddingDim` (1536 here) is decided automatically by the app — it made one real call to your
embedding model to measure the size of the vectors it produces, and locked that number in
permanently for this knowledge base. You never choose it yourself.

Save the id for later:

```bash
export KB_ID="c1127044-05a6-4119-b0f7-5e9146672ce9"
```

Other things you can do with a knowledge base:

```bash
# List all of your knowledge bases
curl -s http://localhost:8080/api/v1/knowledgebase \
  -H "Authorization: Bearer $API_KEY"

# Rename one (its embedding size can never change, only the name)
curl -s -X PUT "http://localhost:8080/api/v1/knowledgebase/$KB_ID" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $API_KEY" \
  -d '{"name": "product-docs-v2"}'

# Delete one (and everything in it — cannot be undone)
curl -s -X DELETE "http://localhost:8080/api/v1/knowledgebase/$KB_ID" \
  -H "Authorization: Bearer $API_KEY"
```

### 3.4 Upload a document

Documents are uploaded as regular file uploads (the same way a website file-upload form works).
Supported file types: PDF, Word (`.doc`/`.docx`), Excel (`.xls`/`.xlsx`), PowerPoint
(`.ppt`/`.pptx`), HTML, images (JPEG/PNG/GIF/BMP/TIFF), and plain text/Markdown. Maximum file size:
20 MB.

```bash
curl -s -X POST "http://localhost:8080/api/v1/knowledgebase/$KB_ID/documents" \
  -H "Authorization: Bearer $API_KEY" \
  -F "file=@/path/to/your/document.pdf;type=application/pdf"
```

Response:

```json
{
  "documentId": "a3395b97-143a-4e0e-aa0a-9da82d3e6d2e",
  "jobId": "0c9428ba-66a1-456c-bbb5-dc626d967324",
  "status": "PENDING",
  "dispatched": true
}
```

This responds **immediately** — the actual processing (reading the file, splitting it into chunks,
generating embeddings, indexing it for search) happens in the background, since it can take a while
for a large file. You get a `jobId` back to check on its progress.

### 3.5 Check on the upload's progress

```bash
curl -s "http://localhost:8080/api/v1/jobs/0c9428ba-66a1-456c-bbb5-dc626d967324" \
  -H "Authorization: Bearer $API_KEY"
```

Response:

```json
{
  "jobId": "0c9428ba-66a1-456c-bbb5-dc626d967324",
  "documentId": "a3395b97-143a-4e0e-aa0a-9da82d3e6d2e",
  "status": "READY",
  "stage": null,
  "errorDetail": null,
  "createdAt": "2026-07-19T21:45:21.816620Z",
  "updatedAt": "2026-07-19T21:45:23.349848Z"
}
```

`status` will be one of: `PENDING` (queued) → `INDEXING` (being processed) → `READY` (done, you can
now search/ask about it) or `FAILED` (something went wrong — check `errorDetail` for why). For a
small document this usually finishes in a second or two; a large PDF with many pages can take a
couple of minutes. Just keep checking every few seconds until it says `READY` or `FAILED`.

You can also look up a document's status directly by its own id, without needing the job id:

```bash
curl -s "http://localhost:8080/api/v1/documents/a3395b97-143a-4e0e-aa0a-9da82d3e6d2e/status" \
  -H "Authorization: Bearer $API_KEY"
```

### 3.6 Search your documents

Once a document is `READY`, you can search it. There are three modes:
- `hybrid` (default, and usually the best) — combines keyword matching and AI semantic search.
- `vector` — pure AI semantic/meaning-based search.
- `keyword` — pure exact keyword search (works even without an embedding model configured).

```bash
curl -s -X POST "http://localhost:8080/api/v1/knowledgebase/$KB_ID/search" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $API_KEY" \
  -d '{"query": "what document formats are supported?"}'
```

Response (a ranked list of matching chunks of text):

```json
[
  {
    "chunkId": "12859d6c-3528-4a99-b47b-6a4ff189ef7b",
    "documentId": "a3395b97-143a-4e0e-aa0a-9da82d3e6d2e",
    "filename": "docs-sample.txt",
    "content": "SynapseMCP is a multi-tenant retrieval-augmented generation platform. It supports PDF, Word, Excel, PowerPoint, HTML, images, and plain text documents.",
    "score": 0.0163934426229508,
    "metadata": { "strategy": "single-chunk" }
  }
]
```

Optional fields you can add to the request body: `"topK": 5` (how many results, default 10),
`"mode": "vector"` (see above), `"rerank": false` (skip the AI re-ranking step, on by default).

### 3.7 Ask a question and get an AI-generated answer

This is the main event — ask a plain-language question, and the app finds the relevant chunks of
your documents and has the AI model write an answer using only that content (with citations, and
it's instructed to say "I don't know" rather than make something up if it can't find an answer).

```bash
curl -s -X POST "http://localhost:8080/api/v1/knowledgebase/$KB_ID/ask" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $API_KEY" \
  -d '{"question": "What document formats does SynapseMCP support?"}'
```

Response:

```json
{
  "answer": "PDF, Word, Excel, PowerPoint, HTML, images, and plain text documents. [Source 1]",
  "citations": [
    {
      "sourceNumber": 1,
      "chunkId": "12859d6c-3528-4a99-b47b-6a4ff189ef7b",
      "documentId": "a3395b97-143a-4e0e-aa0a-9da82d3e6d2e",
      "filename": "docs-sample.txt"
    }
  ]
}
```

Optional fields: `"language": "French"` (respond in a specific language, otherwise it matches your
question's own language automatically), `"history": [{"role":"user","content":"..."},{"role":"assistant","content":"..."}]`
(previous turns of the conversation, for follow-up questions), `"mode"`/`"rerank"` (same as search).

**Streaming version** (the answer arrives word-by-word as it's generated, like watching it being
typed, instead of waiting for the whole thing at once) — add `-N` to curl (so it doesn't wait to
buffer the whole response) and an `Accept: text/event-stream` header:

```bash
curl -s -N -X POST "http://localhost:8080/api/v1/knowledgebase/$KB_ID/ask" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $API_KEY" \
  -H "Accept: text/event-stream" \
  -d '{"question": "What document formats does SynapseMCP support?"}'
```

Output (each `data:` line is one small piece of the answer as it's generated, finishing with a
`citations` event once the answer is complete):

```
data:It

data: supports

data: PDF

data:, Word, Excel, PowerPoint, HTML, images, and plain text documents.

data: [Source 1]

event:citations
data:[{"sourceNumber":1,"chunkId":"12859d6c-3528-4a99-b47b-6a4ff189ef7b","documentId":"a3395b97-143a-4e0e-aa0a-9da82d3e6d2e","filename":"docs-sample.txt"}]
```

### 3.8 Evaluate search quality (optional, for developers/testers)

If you have a set of "golden" questions where you already know which chunk *should* answer them, you
can measure how well retrieval is doing:

```bash
curl -s -X POST "http://localhost:8080/api/v1/knowledgebase/$KB_ID/evaluate" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $API_KEY" \
  -d '{
    "queries": [
      {"query": "document formats", "expectedChunkIds": ["12859d6c-3528-4a99-b47b-6a4ff189ef7b"]}
    ]
  }'
```

Response:

```json
{
  "perQuery": [
    {"query": "document formats", "precisionAtK": 1.0, "recallAtK": 1.0, "reciprocalRank": 1.0}
  ],
  "meanPrecisionAtK": 1.0,
  "meanRecallAtK": 1.0,
  "meanReciprocalRank": 1.0
}
```

A perfect score (`1.0` everywhere) means the expected chunk was always found and ranked first. This
is a development/testing tool, not something a normal end-user would call.

---

## 4. Part B — Using MCP with curl

**Read this first:** in real-world use, you would almost never hand-write these requests yourself.
You'd point a real MCP client — Claude Desktop, an IDE's AI assistant, or any other MCP-compatible
tool — at `http://localhost:8080/mcp` with your username and password, and it handles all of this
automatically. This section exists so you can see exactly what's happening under the hood, test it
yourself, or debug something.

### 4.1 The big idea

MCP lets an AI assistant call the same underlying features as the REST API (create a knowledge base,
upload a document, ask a question, ...) as named "tools" it can decide to use on its own, in
response to what you ask it in plain English.

There are two differences from the REST API worth knowing up front:
- **Login is different.** Instead of an API key in a header, MCP uses a username/password (HTTP
  Basic Auth) — think of it as a separate login system for "AI assistant" access, distinct from the
  API keys tenants use for the REST API.
- **One MCP account can be linked to at most one tenant.** When you register, you either link
  immediately to a tenant you already have (if you already have its API key), or you register
  unlinked and use the special `create_tenant` tool to create a brand-new tenant and link yourself to
  it in one step. Every other tool refuses to work until you're linked to *some* tenant.

### 4.2 Register an MCP user account

**Option 1 — brand new tenant, created via MCP itself:**

```bash
curl -s -X POST http://localhost:8080/api/v1/mcp-users/register \
  -H "Content-Type: application/json" \
  -d '{"username": "alice", "password": "correct-horse-battery-staple", "apiKey": null}'
```

Response:

```json
{
  "id": "b507f1bc-81b9-4668-bb95-665f5e67ccde",
  "username": "alice",
  "tenantId": null
}
```

`tenantId` is `null` — you're registered, but not linked to anything yet. You'll fix that with the
`create_tenant` tool in a moment.

**Option 2 — link immediately to a tenant you already created via the REST API** (Part A above):
pass that tenant's API key as `apiKey` instead of `null`, and you're linked from the moment you
register — no `create_tenant` call needed afterward.

```bash
curl -s -X POST http://localhost:8080/api/v1/mcp-users/register \
  -H "Content-Type: application/json" \
  -d '{"username": "alice", "password": "correct-horse-battery-staple", "apiKey": "your-existing-tenant-api-key"}'
```

### 4.3 Understanding the "Streamable HTTP" protocol (before your first call)

Every MCP request is a `POST` to the *same single URL*, `http://localhost:8080/mcp`, with your
username/password sent as HTTP Basic Auth. The body is always a small JSON envelope describing which
"method" you're calling — this is a standard called JSON-RPC 2.0.

There are two things that trip people up the first time, so let's get them out of the way:

1. **You must "initialize" a session first**, and the server hands you back a `Mcp-Session-Id` in
   the *response headers* (not the body). You must then send that same session id back as a header
   on every following request — think of it like a coat-check ticket.
2. **Some responses come back in a slightly unusual format.** Instead of plain JSON, you'll sometimes
   see something like:
   ```
   id:a32bb890-896f-48d3-981f-b7b837fe926a
   event:message
   data:{"jsonrpc":"2.0", ...actual JSON here... }
   ```
   The real answer is on the `data:` line. (Other times — like the very first `initialize` call — you
   get plain JSON directly with no wrapping at all. Both are normal; just check for a `data:` line
   first, and use it if present.)

A basic auth header is just `username:password`, base64-encoded:

```bash
AUTH=$(echo -n "alice:correct-horse-battery-staple" | base64)
```

### 4.4 Start a session (the "initialize" call)

```bash
curl -sD /tmp/mcp_headers.txt -o /tmp/mcp_body.txt -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Authorization: Basic $AUTH" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2025-06-18",
      "capabilities": {},
      "clientInfo": {"name": "curl-example", "version": "1.0"}
    }
  }'
```

`-D /tmp/mcp_headers.txt` saves the response headers to a file (so we can find the session id);
`-o /tmp/mcp_body.txt` saves the response body separately.

Extract your session id like this:

```bash
export SESSION_ID=$(grep -i "^mcp-session-id:" /tmp/mcp_headers.txt | tr -d '\r' | cut -d' ' -f2)
echo "Session: $SESSION_ID"
```

From here on, **every** request needs both `-H "Authorization: Basic $AUTH"` **and**
`-H "Mcp-Session-Id: $SESSION_ID"`.

### 4.5 A helper function (recommended)

Since every tool call looks almost identical, it's much easier to define one shell function once and
reuse it:

```bash
call_tool() {
  local id="$1" name="$2" args="$3"
  curl -s -X POST http://localhost:8080/mcp \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -H "Authorization: Basic $AUTH" \
    -H "Mcp-Session-Id: $SESSION_ID" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"tools/call\",\"params\":{\"name\":\"$name\",\"arguments\":$args}}" \
    | grep '^data:' | sed 's/^data://'
}
```

Every example below assumes you've defined this function (and set `$AUTH`/`$SESSION_ID` as shown
above). The `id` number just needs to be different each time (or you can reuse `1` — the server
doesn't require them to be unique across your whole session, only within one request/response pair).

### 4.6 See what tools are available

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Authorization: Basic $AUTH" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  | grep '^data:' | sed 's/^data://' | jq -r '.result.tools[].name'
```

Output — the 13 available tools:

```
ask
configure_model
create_knowledge_base
create_tenant
delete_knowledge_base
evaluate
get_document_status
get_tenant
ingest
job_status
list_knowledge_bases
search
update_knowledge_base
```

### 4.7 Link to a tenant (only if you registered without one)

If you registered with `"apiKey": null` (Option 1 above), call `create_tenant` now — this both
creates a brand-new tenant *and* permanently links your MCP account to it in one step:

```bash
call_tool 3 create_tenant '{"name": "Acme Corp via MCP"}' | jq .
```

Response:

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{\"tenantId\":\"8ebe557b-157f-4898-8b9d-f46bb8492cd0\",\"name\":\"Acme Corp via MCP\",\"apiKey\":\"JtYhpWF0qTc-NSKD0kFgoOKGp1aal40UjUmKuT2cKwQ\"}"
      }
    ],
    "isError": false
  }
}
```

Notice the actual result is JSON-*encoded-as-a-string* inside `result.content[0].text` — you'll need
one more `jq` step to get the real fields out of it. Here's the pattern used throughout the rest of
this guide:

```bash
call_tool 3 create_tenant '{"name": "Acme Corp via MCP"}' | jq -r '.result.content[0].text' | jq .
```

**This only works once.** Calling `create_tenant` again on an already-linked account returns a clean
error (`isError: true`) instead — it will never accidentally create you a second tenant.

### 4.8 Configure the model

Same information as the REST API's model-config step, just as tool arguments instead of a JSON body:

```bash
call_tool 4 configure_model '{
  "chatProvider": "openai",
  "chatModel": "gpt-4o",
  "embeddingProvider": "openai",
  "embeddingModel": "text-embedding-3-small",
  "chatApiKey": "sk-your-openai-key",
  "embeddingApiKey": "sk-your-openai-key"
}' | jq -r '.result.content[0].text' | jq .
```

### 4.9 Create a knowledge base

```bash
KB_RESULT=$(call_tool 5 create_knowledge_base '{"name": "support-articles"}')
echo "$KB_RESULT" | jq -r '.result.content[0].text' | jq .
export KB_ID=$(echo "$KB_RESULT" | jq -r '.result.content[0].text' | jq -r '.id')
```

### 4.10 Upload a document

The `ingest` tool accepts **either** raw text **or** file bytes (base64-encoded) — never both at
once. Raw text is the simplest for a quick test:

```bash
INGEST_RESULT=$(call_tool 6 ingest "{
  \"knowledgeBaseId\": \"$KB_ID\",
  \"text\": \"SynapseMCP supports the Model Context Protocol so AI assistants can manage tenants and knowledge bases directly.\"
}")
echo "$INGEST_RESULT" | jq -r '.result.content[0].text' | jq .
export JOB_ID=$(echo "$INGEST_RESULT" | jq -r '.result.content[0].text' | jq -r '.jobId')
```

To upload an actual file instead, base64-encode it yourself and pass `filename` + `contentBase64`:

```bash
CONTENT_B64=$(base64 -i /path/to/your/document.pdf)
call_tool 6 ingest "{\"knowledgeBaseId\": \"$KB_ID\", \"filename\": \"document.pdf\", \"contentBase64\": \"$CONTENT_B64\"}"
```

### 4.11 Check upload progress

```bash
call_tool 7 job_status "{\"jobId\": \"$JOB_ID\"}" | jq -r '.result.content[0].text' | jq .
```

Same `PENDING`/`INDEXING`/`READY`/`FAILED` states as the REST API. Keep polling every few seconds
until it's `READY`.

### 4.12 Search and ask questions

```bash
# Search
call_tool 8 search "{\"knowledgeBaseId\": \"$KB_ID\", \"query\": \"what protocol does synapsemcp support?\"}" \
  | jq -r '.result.content[0].text' | jq .

# Ask a question
call_tool 9 ask "{\"knowledgeBaseId\": \"$KB_ID\", \"question\": \"What protocol does SynapseMCP support for AI assistants?\"}" \
  | jq -r '.result.content[0].text' | jq .
```

The response shapes are identical to their REST equivalents (§3.6/§3.7) — just wrapped inside
`result.content[0].text` as usual. (MCP tool calls don't support the word-by-word streaming version —
that's REST-only, since a "tool result" is a single, complete value, not an open stream.)

### 4.13 Everything else

The remaining tools all follow the exact same `call_tool` pattern:

```bash
# Your tenant's own details and a documents-by-status summary
call_tool 10 get_tenant '{}' | jq -r '.result.content[0].text' | jq .

# All of your knowledge bases
call_tool 11 list_knowledge_bases '{}' | jq -r '.result.content[0].text' | jq .

# Rename a knowledge base
call_tool 12 update_knowledge_base "{\"knowledgeBaseId\": \"$KB_ID\", \"name\": \"support-articles-v2\"}" \
  | jq -r '.result.content[0].text' | jq .

# Check a document's status by its own id (not the job id)
call_tool 13 get_document_status "{\"documentId\": \"<document-id-from-the-ingest-response>\"}" \
  | jq -r '.result.content[0].text' | jq .

# Run golden-query evaluation (same shape as the REST /evaluate endpoint, §3.8)
call_tool 14 evaluate "{\"knowledgeBaseId\": \"$KB_ID\", \"queries\": [{\"query\": \"protocol\", \"expectedChunkIds\": [\"<a-chunk-id-from-search>\"]}]}" \
  | jq -r '.result.content[0].text' | jq .

# Permanently delete a knowledge base (cannot be undone)
call_tool 15 delete_knowledge_base "{\"knowledgeBaseId\": \"$KB_ID\"}" \
  | jq -r '.result.content[0].text' | jq .
```

---

## 5. Common errors and what they mean

Every error from this app follows the same shape (a standard called RFC 7807 "Problem Details"), so
once you recognize the pattern, every error is self-explanatory:

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "knowledge base not found"
}
```

The `detail` field is always a plain-English explanation. Here are the ones you're most likely to
run into, with real examples:

| Status | What it means | Real example |
|---|---|---|
| **400** Bad Request | Something in your request was malformed (bad UUID format, missing required field, etc.) | — |
| **401** Unauthorized | Your API key (or MCP username/password) is missing or wrong | `{"detail":"Missing or malformed Authorization header"}` or `{"detail":"Invalid API key"}` |
| **403** Forbidden | You're trying to access another tenant's data via an endpoint that has the tenant id directly in the URL (only the model-config endpoint works this way) | — |
| **404** Not Found | The thing you asked for doesn't exist — *or* it belongs to a different tenant. Deliberately, the app never tells you which, so nobody can probe around for other people's data | `{"detail":"knowledge base not found"}` |
| **409** Conflict | You tried to create something that already exists (e.g. two knowledge bases with the same name) | — |
| **413** Payload Too Large | Your file/request body is bigger than the app allows (20 MB per document upload; a smaller limit for everything else) | `{"detail":"Maximum upload size exceeded"}` |
| **415** Unsupported Media Type | The file's actual content isn't one of the 7 supported document types — note this checks the *real bytes*, not the file extension, so renaming a `.exe` to `.pdf` won't fool it | `{"detail":"unsupported file type: application/octet-stream"}` |
| **422** Unprocessable Entity | The request was well-formed, but something about your account's state doesn't allow it — almost always "you haven't configured a model yet" | `{"detail":"model config not set for tenant"}` |
| **429** Too Many Requests | You've hit a rate limit. Tenant creation: 5/hour/IP. AI-provider calls (search/ask/upload): 60/minute per tenant per provider. The response includes a `Retry-After` header telling you how many seconds to wait | `{"detail":"Tenant creation rate limit exceeded, try again later"}` |
| **503** Service Unavailable | The database is temporarily unreachable, or a resource is briefly locked by another concurrent request — both are worth just retrying after a moment | — |

**MCP-specific note:** over MCP, errors don't come back as HTTP status codes (there's only ever one
HTTP status, 200, for the transport itself) — instead, look for `"isError": true` in the response,
with the same plain-English message in the accompanying text. For example, calling `create_tenant`
twice on the same account gives you:

```json
{"result": {"content": [{"type": "text", "text": "Error invoking method: createTenant\nThis account is already linked to a tenant."}], "isError": true}}
```

---

## 6. Quick reference

**Full REST endpoint list:**

| Method & Path | Needs auth? | Purpose |
|---|---|---|
| `POST /api/v1/tenants` | No | Create a tenant |
| `PUT /api/v1/tenants/{tenantId}/model-config` | Yes | Set chat/embedding provider + models + keys |
| `GET /api/v1/tenants/{tenantId}/model-config` | Yes | Read current model config (no keys shown) |
| `POST /api/v1/knowledgebase` | Yes | Create a knowledge base |
| `GET /api/v1/knowledgebase` | Yes | List your knowledge bases |
| `PUT /api/v1/knowledgebase/{id}` | Yes | Rename a knowledge base |
| `DELETE /api/v1/knowledgebase/{id}` | Yes | Delete a knowledge base |
| `POST /api/v1/knowledgebase/{id}/documents` | Yes | Upload a document (multipart file) |
| `GET /api/v1/jobs/{jobId}` | Yes | Poll an ingestion job's status |
| `GET /api/v1/documents/{documentId}/status` | Yes | Same, looked up by document id |
| `POST /api/v1/knowledgebase/{id}/search` | Yes | Hybrid/vector/keyword search |
| `POST /api/v1/knowledgebase/{id}/ask` | Yes | Ask a question (JSON or SSE streaming) |
| `POST /api/v1/knowledgebase/{id}/evaluate` | Yes | Golden-query relevance metrics |
| `POST /api/v1/mcp-users/register` | No | Register an MCP (AI-assistant) account |

**Full MCP tool list:** `create_tenant`, `get_tenant`, `configure_model`, `create_knowledge_base`,
`update_knowledge_base`, `delete_knowledge_base`, `list_knowledge_bases`, `ingest`, `job_status`,
`get_document_status`, `search`, `ask`, `evaluate`.

**Key limits to remember:**
- Max 10 knowledge bases per tenant.
- Max 20 MB per uploaded document.
- Max 100 pages get the full AI-vision treatment for PDFs/images; beyond that, a faster
  text-extraction-only fallback is used automatically.
- 5 tenant creations per hour per IP address.
- 60 AI-provider calls per minute per tenant per provider (separately for chat vs. embedding calls).

---

## 7. Stopping the application

If you started it in your terminal with `./mvnw spring-boot:run`, just press `Ctrl+C` in that
terminal window.

If it's running in the background and you're not sure how, find and stop it like this:

```bash
# macOS / Linux
lsof -i :8080          # shows you the process id (PID) using port 8080
kill <PID>             # stop it gracefully
```

Nothing you've uploaded or created is lost by stopping the app — it's all safely stored in Postgres
and Redis. Starting it again picks up exactly where you left off.
