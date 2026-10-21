# Repository instructions

## Required skills

- Always use the `keep-it-simple` skill for every task in this repository.

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
- When the implemented change is verified on `main`, move the issue to `Done`
  without asking.
- A status transition never authorizes committing, pushing, merging, or otherwise
  putting a change on `main`; those actions still require their own authorization.
- Ask before changing status only when the correct transition is ambiguous.

## Backlog prioritization

- Treat every Linear issue in `Backlog` as deliberately deferred.
- Do not present backlog issues as actionable next work or recommend them unless the user explicitly asks to inspect or choose from the backlog, or explicitly names a backlog issue.
- If no open non-backlog ticket remains, report that there is no immediately actionable ticket and suggest creating a regular ticket for the next planned work.
