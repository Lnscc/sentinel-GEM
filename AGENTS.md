# Repository instructions

## Required skills

- Always use the `keep-it-simple` skill for every task in this repository.

## Token efficiency

- Never enumerate all available tools or request bulk tool schemas or metadata,
  including provider-wide listings such as all Linear tools. Resolve only the
  exact required capability.
- Scope repository searches to relevant directories and file types. Exclude
  `.git`, `node_modules`, `target`, `build`, `dist`, generated files, lockfiles,
  source maps, and minified files unless they are explicitly needed.
- Limit command output. Prefer targeted matches, relevant excerpts, and failure
  summaries over complete files or logs.
- Read complete files only when their full context is necessary.
- Fetch Linear issues once per task when possible. Avoid returning or rewriting
  the complete issue description when only a status or section changes.
- Batch independent reads and tool calls.
- Load only required skills and only the supporting material needed for the task.

## Component design

- Keep UI components small and limited to one cohesive responsibility.
- Split components containing multiple independently meaningful UI sections,
  behaviors, or state responsibilities.
- Keep state and side effects in the smallest component that naturally owns them.
- Do not create trivial wrappers or components that only forward props.

## API and Postman

Whenever a REST API endpoint or its request/response contract is added, changed, or removed:

- Update the corresponding Postman collection under `docs/postman` in the same change.
- Add or update Postman tests for the affected status codes and response contract.
- Validate that the resulting Postman collection is valid JSON.

## Work tracking

- Track engineering work in the Linear team `Sentinel GEM` (`GEM`).
- Do not add repository-local ticket files.
- When implementing a Linear issue, summarize the implemented behavior in the issue.
- When implementation work starts on a Linear issue, move it to `In Progress`
  without asking.
- Keep the issue `In Progress` until its verified commit is contained in `main`;
  uncommitted changes on a `main` checkout do not count.
- A status transition never authorizes committing, pushing, merging, or otherwise
  putting a change on `main`; those actions still require their own authorization.
- Ask before changing status only when the correct transition is ambiguous.

## Backlog prioritization

- Treat every Linear issue in `Backlog` as deliberately deferred.
- Do not present backlog issues as actionable next work or recommend them unless the user explicitly asks to inspect or choose from the backlog, or explicitly names a backlog issue.
- If no open non-backlog ticket remains, report that there is no immediately actionable ticket and suggest creating a regular ticket for the next planned work.
