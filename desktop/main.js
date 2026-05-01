const { app, BrowserWindow, Tray, Menu, Notification, clipboard, ipcMain, safeStorage, nativeImage } = require('electron')
const path = require('path')
const crypto = require('crypto')
const { WebSocketServer } = require('ws')
const QRCode = require('qrcode')

let mainWindow = null
let tray = null
let wss = null
let pairingKey = null
let sessionKey = null
let pairingQRData = null

const WS_PORT = 19527
const CODE_TYPES = { SMS: 'sms', TOTP: 'totp' }

function createWindow() {
  const { screen } = require('electron')
  const primaryDisplay = screen.getPrimaryDisplay()
  const { width, height } = primaryDisplay.workAreaSize

  mainWindow = new BrowserWindow({
    width: 320,
    height: 480,
    x: width - 330,
    y: height - 490,
    frame: false,
    transparent: true,
    alwaysOnTop: true,
    skipTaskbar: true,
    resizable: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      nodeIntegration: false,
      contextIsolation: true
    }
  })

  mainWindow.loadFile(path.join(__dirname, 'src', 'index.html'))
  mainWindow.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true })

  mainWindow.on('close', (event) => {
    if (!app.isQuitting) {
      event.preventDefault()
      mainWindow.hide()
    }
  })
}

function createTray() {
  const icon = nativeImage.createEmpty()
  tray = new Tray(icon)
  tray.setToolTip('验证码同步 - 运行中')

  const contextMenu = Menu.buildFromTemplate([
    { label: '显示窗口', click: () => mainWindow && mainWindow.show() },
    { label: '隐藏窗口', click: () => mainWindow && mainWindow.hide() },
    { type: 'separator' },
    { label: '重新配对', click: regeneratePairingKey },
    { type: 'separator' },
    { label: '退出', click: () => { app.isQuitting = true; app.quit() } }
  ])
  tray.setContextMenu(contextMenu)
  tray.on('click', () => {
    if (mainWindow) {
      mainWindow.isVisible() ? mainWindow.hide() : mainWindow.show()
    }
  })
}

function generateSessionKey() {
  return crypto.randomBytes(32).toString('base64')
}

async function regeneratePairingKey() {
  pairingKey = crypto.randomBytes(32).toString('base64')
  sessionKey = generateSessionKey()

  const localIP = getLocalIP()
  const pairingInfo = JSON.stringify({
    host: localIP,
    port: WS_PORT,
    pk: pairingKey,
    sk: sessionKey
  })

  pairingQRData = pairingInfo
  const qrDataURL = await QRCode.toDataURL(pairingInfo, { width: 250, margin: 1 })
  if (mainWindow) {
    mainWindow.webContents.send('pairing-qr', qrDataURL)
  }
}

function getLocalIP() {
  const { networkInterfaces } = require('os')
  const nets = networkInterfaces()
  for (const name of Object.keys(nets)) {
    for (const net of nets[name]) {
      if (net.family === 'IPv4' && !net.internal) {
        return net.address
      }
    }
  }
  return '127.0.0.1'
}

function startWebSocketServer() {
  wss = new WebSocketServer({ port: WS_PORT })

  wss.on('connection', (ws, req) => {
    const clientIP = req.socket.remoteAddress
    let isAuthenticated = false

    ws.on('message', (data) => {
      try {
        const message = JSON.parse(data.toString())

        if (message.type === 'auth') {
          if (message.pairingKey === pairingKey) {
            isAuthenticated = true
            ws.send(JSON.stringify({ type: 'auth_ok', sessionKey }))
          } else {
            ws.send(JSON.stringify({ type: 'auth_fail' }))
            ws.close()
          }
          return
        }

        if (!isAuthenticated) {
          ws.send(JSON.stringify({ type: 'error', message: '未认证' }))
          return
        }

        if (message.type === 'verify_code') {
          const decrypted = decryptMessage(message.payload, sessionKey)
          if (decrypted) {
            const codeData = JSON.parse(decrypted)
            handleVerifyCode(codeData)
          }
        }
      } catch (e) {
        console.error('消息处理错误:', e)
      }
    })

    ws.on('close', () => {
      if (mainWindow) {
        mainWindow.webContents.send('device-disconnected')
      }
    })
  })
}

function decryptMessage(encryptedBase64, keyBase64) {
  try {
    const key = Buffer.from(keyBase64, 'base64')
    const data = Buffer.from(encryptedBase64, 'base64')
    const iv = data.subarray(0, 12)
    const authTag = data.subarray(data.length - 16)
    const ciphertext = data.subarray(12, data.length - 16)

    const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv)
    decipher.setAuthTag(authTag)
    let decrypted = decipher.update(ciphertext, null, 'utf8')
    decrypted += decipher.final('utf8')
    return decrypted
  } catch (e) {
    console.error('解密失败:', e)
    return null
  }
}

function handleVerifyCode(codeData) {
  const { code, source, type, timestamp, label } = codeData

  const codeInfo = {
    code,
    source: source || '未知',
    type: type || CODE_TYPES.SMS,
    timestamp: timestamp || Date.now(),
    label: label || ''
  }

  if (mainWindow) {
    mainWindow.webContents.send('new-code', codeInfo)
  }

  if (type === CODE_TYPES.SMS) {
    showNotification('📩 新验证码', `${code}\n来源: ${source}`)
  }

  clipboard.writeText(code)
}

function showNotification(title, body) {
  if (Notification.isSupported()) {
    new Notification({ title, body, urgency: 'critical' }).show()
  }
}

ipcMain.handle('get-pairing-info', async () => {
  if (!pairingKey) {
    await regeneratePairingKey()
  }
  return {
    host: getLocalIP(),
    port: WS_PORT,
    hasPairingKey: !!pairingKey
  }
})

ipcMain.handle('copy-to-clipboard', (event, text) => {
  clipboard.writeText(text)

  if (mainWindow) {
    mainWindow.webContents.send('copy-feedback')
  }
})

ipcMain.handle('hide-window', () => {
  if (mainWindow) mainWindow.hide()
})

ipcMain.handle('minimize-window', () => {
  if (mainWindow) mainWindow.minimize()
})

ipcMain.handle('regenerate-pairing', async () => {
  await regeneratePairingKey()
  return true
})

app.whenReady().then(async () => {
  createWindow()
  createTray()
  startWebSocketServer()
  await regeneratePairingKey()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})

app.on('before-quit', () => {
  if (wss) wss.close()
})
