const TOTP = (() => {
  const DIGITS = 6
  const PERIOD = 30

  function base32ToBytes(base32) {
    const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
    let bits = ''
    for (let i = 0; i < base32.length; i++) {
      const val = alphabet.indexOf(base32[i].toUpperCase())
      if (val === -1) continue
      bits += val.toString(2).padStart(5, '0')
    }
    const bytes = []
    for (let i = 0; i + 8 <= bits.length; i += 8) {
      bytes.push(parseInt(bits.substring(i, i + 8), 2))
    }
    return new Uint8Array(bytes)
  }

  async function hmacSha1(key, message) {
    const cryptoKey = await crypto.subtle.importKey(
      'raw', key, { name: 'HMAC', hash: 'SHA-1' }, false, ['sign']
    )
    const signature = await crypto.subtle.sign('HMAC', cryptoKey, message)
    return new Uint8Array(signature)
  }

  function dynamicTruncation(hmacResult) {
    const offset = hmacResult[hmacResult.length - 1] & 0x0f
    const binary =
      ((hmacResult[offset] & 0x7f) << 24) |
      ((hmacResult[offset + 1] & 0xff) << 16) |
      ((hmacResult[offset + 2] & 0xff) << 8) |
      (hmacResult[offset + 3] & 0xff)
    return binary % Math.pow(10, DIGITS)
  }

  async function generate(secretBase32, timestamp) {
    const time = timestamp || Math.floor(Date.now() / 1000)
    const counter = Math.floor(time / PERIOD)
    const counterBytes = new Uint8Array(8)
    let c = counter
    for (let i = 7; i >= 0; i--) {
      counterBytes[i] = c & 0xff
      c >>>= 8
    }
    const keyBytes = base32ToBytes(secretBase32)
    const hmac = await hmacSha1(keyBytes, counterBytes)
    const otp = dynamicTruncation(hmac)
    return otp.toString().padStart(DIGITS, '0')
  }

  function getRemainingSeconds() {
    return PERIOD - (Math.floor(Date.now() / 1000) % PERIOD)
  }

  function getPeriodProgress() {
    const elapsed = Math.floor(Date.now() / 1000) % PERIOD
    return (PERIOD - elapsed) / PERIOD
  }

  return { generate, getRemainingSeconds, getPeriodProgress, DIGITS, PERIOD }
})()

if (typeof module !== 'undefined' && module.exports) {
  module.exports = TOTP
}
