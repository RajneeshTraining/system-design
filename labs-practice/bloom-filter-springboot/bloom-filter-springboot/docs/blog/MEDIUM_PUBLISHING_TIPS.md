# Publishing the post on Medium

Files: `BLOOM_FILTER_MEDIUM_POST.md` (text) and `images/` (6 PNG diagrams, referenced in order).

1. **Replace `GITHUB_REPO_URL`** (appears 4 times) with your repository link.
2. **Create a new Medium story** and paste the text. Medium does not read Markdown files directly, so after pasting:
   * Headings: select the text and use Medium's *T* (large heading) button for `##`, small heading for `###`.
   * Code blocks: Medium supports them (type ``` on an empty line, or use the code-block button). For long snippets, readers on Medium often prefer **GitHub Gists** — paste the Gist URL on its own line and Medium embeds it.
   * Inline `code`: use Medium's inline code (Ctrl/Cmd + Alt + 6 in the editor, or the toolbar `<>`).
3. **Upload the images** where each `![...](images/NN-...png)` line appears, in numeric order:
   01 bit array · 02 request flow · 03 memory · 04 false-positive vs load · 05 lifecycle · 06 use case.
   Add a caption under each. Use image 02 (or 01) as the cover image.
4. The post has no Markdown tables (Medium does not render them) — keep it that way if you edit.
5. Suggested tags: `bloom-filter`, `spring-boot`, `system-design`, `java`, `databases`.
6. Suggested subtitle (Medium "Subtitle" field): *A beginner-friendly, step-by-step guide with a complete Spring Boot implementation.*
7. Before publishing, change the "Scenario (illustrative numbers)" paragraph to your own real traffic figures if you have them.

To regenerate or restyle the diagrams, they were produced with matplotlib; the measured numbers in images 03 and 04 come from Guava 33.x on 1M emails.
