---
title: llms.txt and Markdown
description: Machine-readable versions of the documentation for LLMs and AI agents.
---

The site follows the [llms.txt](https://llmstxt.org/) convention.

| URL | Contents | Use it for |
|---|---|---|
| [`/llms.txt`](/llms.txt) | Index: project summary, key facts and links to the sets below | Point an agent at the docs |
| [`/llms-full.txt`](/llms-full.txt) | Every page in one Markdown file | Paste into a long-context model or a project knowledge base |
| [`/llms-small.txt`](/llms-small.txt) | Every page, with notes and tips removed | Smaller context windows |
| [`/_llms-txt/android-sdk.txt`](/_llms-txt/android-sdk.txt) | Android SDK pages and the AdMob guide | Native Android projects |
| [`/_llms-txt/react-native.txt`](/_llms-txt/react-native.txt) | React Native pages and the AdMob guide | React Native projects |
| [`/_llms-txt/rest-api.txt`](/_llms-txt/rest-api.txt) | REST API pages | Custom clients |
| `/<page>.md` | One page as Markdown, for example [`/android/rewarded.md`](/android/rewarded.md) | Focused questions |
| [`/openapi.yaml`](/openapi.yaml) | OpenAPI 3.1 description of the SDK REST API | API clients, code generators, API tools |
| [`/skills/qartvelo-ads/SKILL.md`](/skills/qartvelo-ads/SKILL.md) | Agent skill | Claude and other skill-aware agents |

## Examples

Add the docs to a Cursor chat with `@Docs` and the URL `https://developers.qartvelo.com/llms-full.txt`, or in any assistant:

```text
Read https://developers.qartvelo.com/_llms-txt/android-sdk.txt and add a rewarded ad for the
placement reward_coins to ShopActivity. Grant 50 coins only on confirmed completion.
```

Fetch from a script:

```sh
curl -s https://developers.qartvelo.com/llms-full.txt -o qartvelo-ads-docs.md
```

The files are regenerated on every docs deployment, so they always match the current SDK version.
