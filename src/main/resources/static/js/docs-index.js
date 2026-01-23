function parseMarkdownWithTables(md) {
    let html = md;

    html = html.replace(/^\|(.+)\|\s*\n\|[-:\s|]+\|\s*\n((?:\|.+\|\s*\n?)+)/gm, function(match, header, body) {
        const headers = header.split('|').map(h => h.trim()).filter(h => h);
        const rows = body.trim().split('\n').map(row =>
            row.split('|').map(cell => cell.trim()).filter(cell => cell)
        );

        let table = '<table><thead><tr>';
        headers.forEach(h => table += `<th>${h}</th>`);
        table += '</tr></thead><tbody>';
        rows.forEach(row => {
            table += '<tr>';
            row.forEach(cell => table += `<td>${cell}</td>`);
            table += '</tr>';
        });
        table += '</tbody></table>';
        return table;
    });

    html = html.replace(/^#### (.*$)/gm, '<h4>$1</h4>');
    html = html.replace(/^### (.*$)/gm, '<h3>$1</h3>');
    html = html.replace(/^## (.*$)/gm, '<h2>$1</h2>');
    html = html.replace(/^# (.*$)/gm, '<h1>$1</h1>');

    html = html.replace(/```(\w*)\n([\s\S]*?)```/g, '<pre><code>$2</code></pre>');
    html = html.replace(/`([^`]+)`/g, '<code>$1</code>');
    html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>');
    html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
    html = html.replace(/^---$/gm, '<hr>');
    html = html.replace(/^- (.*)$/gm, '<li>$1</li>');
    html = html.replace(/(<li>.*<\/li>\n?)+/g, '<ul>$&</ul>');
    html = html.replace(/\n\n(?!<)/g, '</p><p>');
    html = html.replace(/\n(?!<)/g, '<br>');

    if (!html.startsWith('<')) {
        html = '<p>' + html + '</p>';
    }

    return html;
}

async function loadDocsIndex() {
    const container = document.getElementById('docs-index-content');
    if (!container) return;

    try {
        const response = await fetch('/docs-index.md');
        if (!response.ok) {
            throw new Error('docs-index.md not found');
        }

        const markdown = await response.text();
        container.innerHTML = parseMarkdownWithTables(markdown);
    } catch (error) {
        console.error('Error loading docs index:', error);
        container.innerHTML = '';

        const errorEl = document.createElement('div');
        errorEl.className = 'error';

        const title = document.createElement('strong');
        title.textContent = 'Error loading documentation index';
        errorEl.appendChild(title);
        errorEl.appendChild(document.createElement('br'));

        const message = document.createElement('span');
        message.textContent = error.message;
        errorEl.appendChild(message);
        errorEl.appendChild(document.createElement('br'));
        errorEl.appendChild(document.createElement('br'));

        const hint = document.createElement('span');
        hint.textContent = 'The docs-index.md file should be available in static content.';
        errorEl.appendChild(hint);

        container.appendChild(errorEl);
    }
}

document.addEventListener('DOMContentLoaded', loadDocsIndex);
