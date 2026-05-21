# Tactical VoIP DSP Audio Signal Conditioning

This reference document outlines the mathematical and digital signal processing (DSP) design of the **High-Pass Filter (HPF)** and **Dynamic Soft Limiter** systems implemented in the codebase.

---

## 1. High-Pass Filter (HPF)

### 1.1 Physical & Analog Foundations
An analog high-pass filter is physically modeled as an **RC Series Circuit** where the output is taken across the resistor $R$.

```
       C (Capacitor)
Vin ----||------------+----- Vout
                      |
                     [R] Resistor
                      |
                     GND
```

Using Kirchhoff’s Voltage Law (KVL), the differential equation describing the relationship between the input voltage $V_{\text{in}}(t)$ and the output voltage $V_{\text{out}}(t)$ is:

$$V_{\text{out}}(t) = R \cdot I(t) = R C \frac{d}{dt}\left[ V_{\text{in}}(t) - V_{\text{out}}(t) \right]$$

Defining the time constant $\tau = R C$, we have:

$$V_{\text{out}}(t) = \tau \left( \frac{d V_{\text{in}}}{dt} - \frac{d V_{\text{out}}}{dt} \right)$$

### 1.2 Digital Discretization (Difference Equation)
To implement this filter on a discrete-time sequence of audio samples with a sampling rate of $f_s$ (where $T_s = \frac{1}{f_s}$ is the sampling period), we approximate the continuous derivatives using the **Backward Euler Finite Difference Method**:

$$\frac{d V(t)}{dt} \approx \frac{V[n] - V[n-1]}{T_s}$$

Substituting the discrete difference approximations into the differential equation yields:

$$y[n] = \tau \left( \frac{x[n] - x[n-1]}{T_s} - \frac{y[n] - y[n-1]}{T_s} \right)$$

Multiply both sides by $T_s$:

$$y[n] \cdot T_s = \tau \left( x[n] - x[n-1] \right) - \tau \left( y[n] - y[n-1] \right)$$

Group the $y[n]$ terms onto the left side:

$$y[n] \left( T_s + \tau \right) = \tau \left( y[n-1] + x[n] - x[n-1] \right)$$

Divide both sides by $(T_s + \tau)$ to find the standard digital filter difference equation:

$$y[n] = \left( \frac{\tau}{\tau + T_s} \right) \cdot \left( y[n-1] + x[n] - x[n-1] \right)$$

By substituting the digital smoothing coefficient factor $\alpha$:

$$\alpha = \frac{\tau}{\tau + T_s}$$

We obtain the canonical **First-Order Infinite Impulse Response (IIR) High-Pass Filter** equation:

$$y[n] = \alpha \cdot \left( y[n-1] + x[n] - x[n-1] \right)$$

### 1.3 Calibrating the Cut-Off Frequency ($f_c$)
The relationship between the cutoff frequency $f_c$ in Hertz, the time constant $\tau$, and the sampling frequency $f_s$ is given by:

$$\tau = \frac{1}{2\pi f_c}$$

By substituting $\tau$ back into the definition of $\alpha$:

$$\alpha = \frac{\frac{1}{2\pi f_c}}{\frac{1}{2\pi f_c} + \frac{1}{f_s}} = \frac{1}{1 + \frac{2\pi f_c}{f_s}}$$

For a sample rate of $f_s = 16000\text{ Hz}$ and variable cutoff settings, the filter's characteristic coefficient is computed at runtime dynamically:

$$\alpha = \frac{1}{1.0 + \left( \frac{2 \pi \cdot f_c}{16000} \right)}$$

For example, when $f_c = 120\text{ Hz}$:
$$\alpha \approx \frac{1}{1.0 + 0.04712} \approx 0.955$$

Any low frequency sound (wind rumbling, engine drone) below the designated $f_c$ is attenuated at a rate of **6 dB/octave (first-order slope)**, preventing sub-bass clutter from falsely triggering Voice Activity Detection (VOX).

### 1.4 State Preservation
To prevent window transition cracking or popping artifacts, the filter state registers:
- $lastHpfInput = x[n-1]$
- $lastHpfOutput = y[n-1]$

are persisted in memory globally across discrete audio buffer chunk callbacks.

---

## 2. Real-time Gain Booster

When software gain multiplication is requested by the operator, the input signal $s[n]$ is magnified by the factor $G$:

$$s_{\text{boosted}}[n] = s[n] \cdot G \quad \text{where } G \in [1.0, 5.0]$$

If unchecked, multiplying signals in this fashion introduces severe digital clipping (clamping hard at $y = \pm 1.0$), resulting in high-frequency square wave harmonics that physically damage headset tweeters and cause auditory strain.

---

## 3. Dynamic Soft Limiter

To eliminate hard distortion spikes, a customized, algebraic wave-shaping soft limiter is chained downstream of the digital boosters.

### 3.1 Design Constraints
1. **Linear Unity Region**: For signal levels up to a designated threshold $T \in [0.5, 0.99]$, the limiter remains completely transparent (unity gain of $1.0$).
2. **Saturating Compression Region**: Above $T$, overshoot peaks are continuously compressed along an asymptotic mathematical shoulder bounding the output strictly to $\pm 1.0$.
3. **Smooth ( $C^1$ Continuous) Transition**: The transition boundary at $s[n] = \pm T$ must have a continuous first derivative to avoid creating artificial harmonic distortions (avoiding hard-knee knee profiles).

### 3.2 The Mathematical Formula
Let $s$ represent a normalized sample value in the range $[-1.0, 1.0]$:

$$\text{If } |s| \le T: \quad y = s$$

$$\text{If } |s| > T: \quad y = \text{sgn}(s) \cdot \left( T + (1 - T) \cdot \frac{|s| - T}{(1 - T) + (|s| - T)} \right)$$

Let $e = |s| - T$ denote the **envelope overshoot excess**. Substituting $e$ into the formulation:

$$y = \text{sgn}(s) \cdot \left( T + (1 - T) \cdot \frac{e}{(1 - T) + e} \right)$$

### 3.3 Asymptotic Validation Proof
We analyze the compression behavior of the excess function $f(e) = \frac{e}{(1 - T) + e}$ to ensure it never allows $y$ to exceed $\pm 1.0$:

* **Lower Bound Boundary (as $e \to 0$):**
  $$\lim_{e \to 0} y = \text{sgn}(s) \cdot (T + 0) = \text{sgn}(s) \cdot T$$
  This matches the linear region seamlessly.

* **Upper Bound Asymptotic Limit (as $s \to \infty \implies e \to \infty$):**
  $$\lim_{e \to \infty} f(e) = \lim_{e \to \infty} \frac{1}{\frac{1 - T}{e} + 1} = 1$$
  Substituting this limit back into the mapping:
  $$\lim_{s \to \infty} y = \text{sgn}(s) \cdot \left[ T + (1 - T) \cdot 1 \right] = \text{sgn}(s) \cdot 1.0$$
  This mathematically proves that the output is strictly bounded to the interval $\lbrack -1.0, 1.0 \rbrack$ regardless of the boost magnitude, achieving **absolutely zero hard clipping**.

### 3.4 First Derivative $C^1$ Continuity Proof
We verify that the slope of the compression curve matches the linear region (slope $= 1.0$) at the boundary $e = 0$:

Let $L = 1 - T$ (representing remaining digital headroom). The mapping function of the excess is:

$$h(e) = L \cdot \left( \frac{e}{L + e} \right)$$

Take the derivative of $h(e)$ with respect to $e$ using the Quotient Rule:

$$h'(e) = L \cdot \frac{d}{de}\left( \frac{e}{L + e} \right) = L \cdot \frac{(L + e) \cdot 1 - e \cdot 1}{(L + e)^2} = \frac{L^2}{(L + e)^2}$$

Evaluate the derivative at the transition boundary $e = 0$:

$$h'(0) = \frac{L^2}{(L + 0)^2} = \frac{L^2}{L^2} = 1.0$$

Since the derivative of the linear portion is also $1.0$, the first derivative is perfectly continuous across the threshold boundary $T$. This mathematically guarantees a **warm, analog-like tube distortion** as the signal enters compression, preserving clean tactical communication audio characteristics under extreme boost configs.
