window.renderDevices = function() {
    const list = Object.values(window.getDevices());
    let html = `<div class="card"><h2>Устройства</h2><div class="card-grid">`;
    if (list.length === 0) {
        html += `<p>Нет устройств</p>`;
    } else {
        list.forEach(d => {
            const statusClass = d.status === 'online' ? 'online' : 'offline';
            html += `<div class="device-item" data-code="${d.code}">
                <strong>${d.name}</strong><br>
                Код: ${d.code}<br>
                <span class="status ${statusClass}">${d.status}</span><br>
                IP: ${d.ip}<br>
                VPN: ${d.vpn ? 'Да' : 'Нет'}
            </div>`;
        });
    }
    html += `</div></div>`;
    document.getElementById('content').innerHTML = html;
    document.querySelectorAll('.device-item').forEach(el => {
        el.addEventListener('click', function() {
            window.setCurrentDevice(this.dataset.code);
            window.renderControlPanel(this.dataset.code);
        });
    });
};
