const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('electronAPI', {
  getPairingInfo: () => ipcRenderer.invoke('get-pairing-info'),
  copyToClipboard: (text) => ipcRenderer.invoke('copy-to-clipboard', text),
  hideWindow: () => ipcRenderer.invoke('hide-window'),
  minimizeWindow: () => ipcRenderer.invoke('minimize-window'),
  regeneratePairing: () => ipcRenderer.invoke('regenerate-pairing'),

  onNewCode: (callback) => {
    ipcRenderer.on('new-code', (event, data) => callback(data))
  },
  onPairingQR: (callback) => {
    ipcRenderer.on('pairing-qr', (event, data) => callback(data))
  },
  onCopyFeedback: (callback) => {
    ipcRenderer.on('copy-feedback', () => callback())
  },
  onDeviceDisconnected: (callback) => {
    ipcRenderer.on('device-disconnected', () => callback())
  }
})
