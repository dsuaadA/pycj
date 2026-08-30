window.renderSettings = function() {
    let html = `<div class="card"><h2>Настройки</h2>
        <button id="deleteAll" style="background:#dc3545; color:#fff; border:none; padding:10px 20px; border-radius:5px; cursor:pointer;">Удалить все устройства</button>
        <br><br>
        <button id="toggleTheme" style="background:#6c757d; color:#fff; border:none; padding:10px 20px; border-radius:5px; cursor:pointer;">Сменить тему</button>
    </div>`;
    document.getElementById('content').innerHTML = html;
    document.getElementById('deleteAll').onclick = function() {
        if (confirm('Удалить все устройства?')) {
            fetch('/api/devices', { method: 'DELETE' }).then(() => window.loadDevices());
        }
    };
    document.getElementById('toggleTheme').onclick = function() {
        document.body.classList.toggle('dark');
    };
};
