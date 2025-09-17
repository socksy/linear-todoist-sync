# Linear -> Todoist Sync

A script I've written (with occasional LLM help) for syncing my assigned tasks 
on linear over to Todoist (where I keep my personal todos). Maybe this could be
useful for others too.

## Setup

1. Install [Babashka](https://babashka.org/)
2. Copy `secrets.edn.example` to `secrets.edn` and add your API keys
3. Optional: Copy `config.edn.example` to `config.edn` for LLM processing

Get API keys:
- Linear: Settings -> API -> Create Personal API Key
- Todoist: Settings -> Integrations -> API token

## LLM Processing of tasks

### Why
So I try to do something like Getting Things Done on todoist (I'll get there
eventually...). One thing that that entails is making sure that each item
processed in the inbox is turned into one or more "next actions", each being
something actionable that can be done "next". In my experience, sometimes my
Linear issues meet this criteria, but often times not.

I thought it would be neat to be able to separate the two automatically. And
that it would be even neater if I could do it with a local LLM and not submit
my tasks to Big LLM™. And that this could be a generalized solution for anyone
that wants to process their issues semantically. So I have it set up that it
runs a prompt of your choosing on every imported issue. The prompt should be
set up to ask it to return a json string {"answer_is_yes": true} (or false,
obviously). If it's yes, then it will tag with a tag name of your choosing.

### Good grammar
As an aside, I initially had this working with a JSON grammar, which may
still be worth pursuing. The problem there is that you can't use the thinking
in the thinking models, as they are trained to return `<thinking>` tags, which
obviously don't fit the grammar. And that without thinking, the small models
seem almost always wrong when they processed whether something was a well
formed next action or not. In theory, you can run it once with the thinking
model, then get a non-thinking model to summarize it with the grammar, which
can be quite effective. But at least locally, for me using LM Studio, this
was taking far too long.

### Caching
To prevent this taking forever every time, we cache which tasks the LLM has
already processed in `.llm-cache.edn`. This is really dumb and doesn't do
anything like a content hash, so if the issue changed you might wanna remove
it from their (or delete this cache).

## Usage

Possible arguments:
```bash
bb sync
bb sync --skip-llm
bb sync --dry-run
bb sync --verbose
bb sync --help
```

They all do what they look like they do.

## What it does

- Creates Todoist tasks for assigned Linear issues
- Completes tasks when Linear issues are done
- Updates task content when issues change
- Marks reassigned issues as "REASSIGNED:" and completes them
- Maps Linear priorities to Todoist (Urgent→P1, High→P2, etc.)
- Uses an LLM to process tasks according to a given prompt, and uses the answer
  to tag the task appropriately (optional)

## Configuration

**secrets.edn** (required):
```clojure
{:linear {:api-key "lin_api_..."}
 :todoist {:api-key "..."}}
```

**config.edn** (optional):
```clojure
{:todoist {:project-name "Work Inbox"} ;; Project to create tasks in (defaults to Inbox)
 ;; Additional labels you might want to add to every single imported task
 :additional-labels ["work"]
 :llm {:enabled true
       :base-url "http://127.0.0.1:1234/v1"
       :model "awesomeness-classifier-2507"
       :prompt "Is this task awesome?"
       :labels-to-add ["is-awesome"]}}
```

## Licence

Mozilla Public License v2.0
