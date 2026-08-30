(function() {
    let ws = null;
    let devicesData = {};
    let currentCode = null;

    const loginDiv = document.getElementById('login');
    const appDiv = document.getElementById('app');
    const password = document.getElementById('password');
    const secret = document.getElementById('secret');
    const loginBtn = document.getElementById('loginBtn');
    const loginError = document.getElementById('loginError');
    const logoutBtn = document.getElementById('logoutBtn');
    const burgerBtn = document.getElementById('burgerBtn');
    const navMenu = document.getElementById('navMenu');
    const content = document.getElementById('content');

    function showPage(page) {
        if (page === 'devices') window.renderDevices();
        else if (page === 'settings') window.renderSettings();
        else if (page === 'history') window.renderHistory();
        else if (page === 'create') window.renderCreate();
        else if (page === 'media') window.renderMedia();
        else content.innerHTML = '<div class="card"><h2>Главное меню</h2></div>';
        navMenu.classList.remove('active');
    }

    function loadDevices() {
        fetch('/api/devices')
            .then(r => r.json())
            .then(data => {
                devicesData = {};
                data.forEach(d => devicesData[d.code] = d);
                if (document.querySelector('.device-grid')) window.renderDevices();
            });
    }

    loginBtn.addEventListener('click', function() {
        if (password.value === 'rootsql123' && secret.value === 'root') {
            loginDiv.style.display = 'none';
            appDiv.style.display = 'block';
            connectWebSocket();
            loadDevices();
            showPage('devices');
        } else {
            loginError.textContent = 'Неверные данные';
        }
    });

    logoutBtn.addEventListener('click', function() {
        if (ws) ws.close();
        loginDiv.style.display = 'flex';
        appDiv.style.display = 'none';
    });

    burgerBtn.addEventListener('click', function() {
        navMenu.classList.toggle('active');
    });

    document.addEventListener('click', function(e) {
        if (e.target.matches('.nav-menu a[data-page]')) {
            e.preventDefault();
            const page = e.target.getAttribute('data-page');
            if (page === 'devices') loadDevices();
            showPage(page);
        }
    });

    function connectWebSocket() {
        ws = new WebSocket('wss://kriptoman.onrender.com');
        ws.onopen = function() {
            ws.send(JSON.stringify({ type: 'auth', password: 'rootsql123', secret: 'root' }));
        };
        ws.onmessage = function(event) {
            try {
                const data = JSON.parse(event.data);
                if (data.type === 'auth_success') console.log('WebSocket авторизован');
                if (data.type === 'devices') {
                    devicesData = {};
                    data.devices.forEach(d => devicesData[d.code] = d);
                    if (document.querySelector('.device-grid')) window.renderDevices();
                }
            } catch(e) {}
        };
        ws.onclose = function() {
            setTimeout(connectWebSocket, 3000);
        };
    }

    window.getWS = function() { return ws; };
    window.getDevices = function() { return devicesData; };
    window.setCurrentDevice = function(code) { currentCode = code; };
    window.getCurrentDevice = function() { return currentCode; };
    window.showPage = showPage;
    window.loadDevices = loadDevices;
})();
