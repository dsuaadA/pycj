window.renderMedia = function() {
    const container = document.getElementById('content');
    container.innerHTML = `<div class="card"><h2>Файлы (скриншоты и видео)</h2>
        <button id="refreshMediaBtn" style="margin-bottom:15px; padding:8px 16px; background:#007bff; color:#fff; border:none; border-radius:5px; cursor:pointer;">Обновить</button>
        <div id="mediaList"><p>Загрузка...</p></div>
    </div>`;

    document.getElementById('refreshMediaBtn').addEventListener('click', loadMedia);

    function loadMedia() {
        fetch('/api/media')
            .then(r => r.json())
            .then(files => {
                const list = document.getElementById('mediaList');
                if (files.length === 0) {
                    list.innerHTML = '<p>Нет файлов</p>';
                    return;
                }
                let html = '<div style="display:flex; flex-wrap:wrap; gap:15px;">';
                files.forEach(f => {
                    const isImage = f.filename.match(/\.(png|jpg|jpeg)$/i);
                    const isVideo = f.filename.match(/\.(mp4|webm|avi)$/i);
                    const date = new Date(f.timestamp).toLocaleString();
                    html += `<div style="border:1px solid #ccc; padding:10px; border-radius:8px; max-width:200px; text-align:center;">
                        ${isImage ? `<img src="${f.url}" style="max-width:100%; max-height:150px; border-radius:4px;">` : ''}
                        ${isVideo ? `<video src="${f.url}" controls style="max-width:100%; max-height:150px;"></video>` : ''}
                        ${!isImage && !isVideo ? `<div style="font-size:40px;">📄</div>` : ''}
                        <div style="font-size:12px; margin-top:5px;"><a href="${f.url}" target="_blank">${f.filename}</a></div>
                        <div style="font-size:10px; color:#666;">${date}</div>
                    </div>`;
                });
                html += '</div>';
                list.innerHTML = html;
            })
            .catch(() => {
                document.getElementById('mediaList').innerHTML = '<p>Ошибка загрузки файлов</p>';
            });
    }

    loadMedia();
};
