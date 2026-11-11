/* Lumen 变调 / 声场处理器 —— 跑在音频线程（AudioWorklet），
   与界面渲染完全隔离，杜绝主线程繁忙导致的欠载爆音。 */
class LumenPitchProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.ratio = 1;      // 2^(semitones/12)
    this.width = 1;      // 立体声宽度倍率
    this.W = 2048;       // 单侧最大延迟（样本）
    this.BUFS = 16384;
    this.bufL = new Float32Array(this.BUFS);
    this.bufR = new Float32Array(this.BUFS);
    this.wp = 0;
    this.phase = 0;
    this.port.onmessage = e => {
      const d = e.data || {};
      if (typeof d.ratio === 'number') this.ratio = d.ratio;
      if (typeof d.width === 'number') this.width = d.width;
    };
  }

  process(inputs, outputs) {
    const input = inputs[0];
    const output = outputs[0];
    if (!input || !input.length || !output || !output.length) return true;
    const inL = input[0];
    const inR = input.length > 1 ? input[1] : inL;
    const outL = output[0];
    const outR = output.length > 1 ? output[1] : outL;
    const n = inL.length;
    const ratio = this.ratio, wid = this.width;
    const W = this.W, BUFS = this.BUFS;
    const active = Math.abs(ratio - 1) > 1e-4;
    const wide = Math.abs(wid - 1) > 1e-3;

    for (let i = 0; i < n; i++) {
      let l = inL[i], r = inR[i];

      if (active) {
        this.bufL[this.wp] = l;
        this.bufR[this.wp] = r;
        this.phase += (1 - ratio) / W;
        if (this.phase >= 1) this.phase -= 1;
        else if (this.phase < 0) this.phase += 1;

        const t1 = this.phase, t2 = (this.phase + 0.5) % 1;
        const g1 = Math.sin(Math.PI * t1), g2 = Math.sin(Math.PI * t2);
        const p1 = (this.wp - t1 * W + BUFS * 2) % BUFS;
        const p2 = (this.wp - t2 * W + BUFS * 2) % BUFS;
        const i1 = p1 | 0, i2 = p2 | 0;
        const f1 = p1 - i1, f2 = p2 - i2;
        const n1 = (i1 + 1) % BUFS, n2 = (i2 + 1) % BUFS;
        const s1L = this.bufL[i1] + (this.bufL[n1] - this.bufL[i1]) * f1;
        const s1R = this.bufR[i1] + (this.bufR[n1] - this.bufR[i1]) * f1;
        const s2L = this.bufL[i2] + (this.bufL[n2] - this.bufL[i2]) * f2;
        const s2R = this.bufR[i2] + (this.bufR[n2] - this.bufR[i2]) * f2;

        l = s1L * g1 + s2L * g2;
        r = s1R * g1 + s2R * g2;
        this.wp = (this.wp + 1) % BUFS;
      }

      if (wide) {
        const m = (l + r) * 0.5, sd = (l - r) * 0.5 * wid;
        l = m + sd; r = m - sd;
      }

      outL[i] = l;
      outR[i] = r;
    }
    return true;
  }
}
registerProcessor('lumen-pitch', LumenPitchProcessor);
