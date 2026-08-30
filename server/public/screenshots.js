window.renderMedia = function() {
    fetch('/api/media')
        .then(r => r.json())
        .then(files => {
            let html = `<div class="card"><h2>Файлы</h2>`;
            if (files.length === 0) {
                html += `<p>Нет файлов</p>`;
            } else {
                html += `<ul>`;
                files.forEach(f => {
                    html += `<li><a href="${f.url}" target="_blank">${f.filename}</a> - ${new Date(f.timestamp).toLocaleString()}</li>`;
                });
                html += `</ul>`;
            }
            html += `</div>`;
            document.getElementById('content').innerHTML = html;
        })
        .catch(() => {
            document.getElementById('content').innerHTML = `<div class="card"><h2>Ошибка загрузки</h2></div>`;
        });
};
