const codes = []
const totpEntries = new Map()
let totpIntervals = new Map()

document.addEventListener('DOMContentLoaded', async () => {
  setupTitlebar()
  await loadPairingQR()

  window.electronAPI.onNewCode((codeInfo) => {
    addCode(codeInfo)
  })

  window.electronAPI.onPairingQR((qrDataURL) => {
    document.getElementById('qr-image').src = qrDataURL
  })

  window.electronAPI.onCopyFeedback(() => {
    showCopyFeedback()
  })

  window.electronAPI.onDeviceDisconnected(() => {
    updateConnectionStatus(false)
  })

  document.getElementById('btn-refresh-qr').addEventListener('click', async () => {
    await window.electronAPI.regeneratePairing()
  })
})

function setupTitlebar() {
  document.getElementById('btn-min').addEventListener('click', () => {
    window.electronAPI.minimizeWindow()
  })
  document.getElementById('btn-close').addEventListener('click', () => {
    window.electronAPI.hideWindow()
  })
}

async function loadPairingQR() {
  const info = await window.electronAPI.getPairingInfo()
  updateConnectionStatus(false)
}

function addCode(codeInfo) {
  const now = Date.now()
  const duplicate = codes.find(c => c.code === codeInfo.code && (now - c.timestamp) < 30000)
  if (duplicate) return

  codeInfo.id = `code-${now}-${Math.random().toString(36).substr(2, 6)}`
  codes.unshift(codeInfo)

  if (codes.length > 50) codes.length = 50

  if (codeInfo.type === 'totp') {
    updateTotpDisplay()
  } else {
    renderCodes()
    updateConnectionStatus(true)
  }
}

function renderCodes() {
  const smsCodes = codes.filter(c => c.type !== 'totp')
  const container = document.getElementById('codes-list')

  if (smsCodes.length === 0) {
    container.innerHTML = '<div class="empty-state">等待接收验证码...</div>'
    return
  }

  container.innerHTML = smsCodes.map(c => {
    const time = new Date(c.timestamp)
    const timeStr = `${time.getHours().toString().padStart(2,'0')}:${time.getMinutes().toString().padStart(2,'0')}:${time.getSeconds().toString().padStart(2,'0')}`
    return `
      <div class="code-item ${Date.now() - c.timestamp < 3000 ? 'new-code' : ''}" data-id="${c.id}">
        <div>
          <div class="code-value">${escapeHtml(c.code)}</div>
          <div class="code-source">${escapeHtml(c.source)}</div>
        </div>
        <div style="text-align:right">
          <div class="code-time">${timeStr}</div>
          <div class="code-actions">
            <button onclick="copyCode('${escapeHtml(c.code)}', event)">📋 复制</button>
          </div>
        </div>
      </div>
    `
  }).join('')

  container.querySelectorAll('.code-item').forEach(item => {
    item.addEventListener('click', function(e) {
      if (e.target.tagName === 'BUTTON') return
      const codeEl = this.querySelector('.code-value')
      if (codeEl) {
        window.electronAPI.copyToClipboard(codeEl.textContent)
      }
    })
  })
}

function updateTotpDisplay() {
  const totpCodes = codes.filter(c => c.type === 'totp')
  const container = document.getElementById('totp-list')

  Object.keys(totpIntervals).forEach(key => {
    clearInterval(totpIntervals[key])
  })
  totpIntervals = {}

  if (totpCodes.length === 0) {
    container.innerHTML = '<div class="empty-state">暂无 TOTP 验证码</div>'
    return
  }

  const latestTotps = new Map()
  totpCodes.forEach(c => {
    if (!latestTotps.has(c.label) || c.timestamp > latestTotps.get(c.label).timestamp) {
      latestTotps.set(c.label, c)
    }
  })

  container.innerHTML = Array.from(latestTotps.values()).map(c => {
    const progress = TOTP.getPeriodProgress()
    const circumference = 2 * Math.PI * 16
    const offset = circumference * (1 - progress)
    const remaining = TOTP.getRemainingSeconds()
    const id = `totp-${c.label.replace(/[^a-zA-Z0-9]/g, '_')}`
    return `
      <div class="totp-item" data-id="${id}">
        <div class="totp-progress">
          <svg width="40" height="40" viewBox="0 0 40 40">
            <circle class="bg" cx="20" cy="20" r="16"></circle>
            <circle class="fg" cx="20" cy="20" r="16"
              stroke-dasharray="${circumference}" stroke-dashoffset="${offset}"></circle>
            <text class="remaining" x="20" y="20" dy=".35em">${remaining}s</text>
          </svg>
        </div>
        <div class="totp-label">${escapeHtml(c.label || 'TOTP')}</div>
        <div class="totp-code">${escapeHtml(c.code)}</div>
      </div>
    `
  }).join('')

  container.querySelectorAll('.totp-item').forEach(item => {
    item.addEventListener('click', function() {
      const codeEl = this.querySelector('.totp-code')
      if (codeEl) {
        window.electronAPI.copyToClipboard(codeEl.textContent)
      }
    })
  })

  latestTotps.forEach((c) => {
    const id = `totp-${c.label.replace(/[^a-zA-Z0-9]/g, '_')}`
    setupTotpProgressUpdate(id, c)
  })
}

function setupTotpProgressUpdate(id, codeInfo) {
  const intervalKey = id
  totpIntervals[intervalKey] = setInterval(() => {
    const progress = TOTP.getPeriodProgress()
    const remaining = TOTP.getRemainingSeconds()
    const item = document.querySelector(`.totp-item[data-id="${id}"]`)
    if (!item) {
      clearInterval(totpIntervals[intervalKey])
      return
    }
    const circle = item.querySelector('.fg')
    const text = item.querySelector('.remaining')
    if (circle) {
      const circumference = 2 * Math.PI * 16
      circle.setAttribute('stroke-dashoffset', circumference * (1 - progress))
      if (remaining <= 5) {
        circle.style.stroke = '#e06060'
      } else {
        circle.style.stroke = '#5cdb8b'
      }
    }
    if (text) {
      text.textContent = `${remaining}s`
    }
  }, 1000)
}

function updateConnectionStatus(connected) {
  const statusEl = document.getElementById('connection-status')
  if (connected) {
    statusEl.className = 'status-connected'
    statusEl.textContent = '● 已连接'
  } else {
    statusEl.className = 'status-disconnected'
    statusEl.textContent = '● 未连接'
  }
}

function copyCode(code, event) {
  if (event) event.stopPropagation()
  window.electronAPI.copyToClipboard(code)
}

function showCopyFeedback() {
  const existing = document.querySelector('.copy-feedback')
  if (existing) existing.remove()

  const feedback = document.createElement('div')
  feedback.className = 'copy-feedback'
  feedback.textContent = '✅ 已复制!'
  document.body.appendChild(feedback)
  setTimeout(() => feedback.remove(), 1000)
}

function escapeHtml(text) {
  if (!text) return ''
  const div = document.createElement('div')
  div.textContent = text
  return div.innerHTML
}

window.copyCode = copyCode
