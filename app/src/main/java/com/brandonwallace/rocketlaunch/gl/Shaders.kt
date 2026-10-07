package com.brandonwallace.rocketlaunch.gl

object Shaders {
    const val LIT_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNor;
uniform mat4 uMVP;
uniform mat4 uModel;
out vec3 vN;
out vec3 vW;
void main() {
    vW = (uModel * vec4(aPos, 1.0)).xyz;
    vN = mat3(uModel) * aNor;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""

    const val LIT_FS = """#version 300 es
precision highp float;
in vec3 vN;
in vec3 vW;
uniform vec3 uColor;
uniform vec3 uEmissive;
uniform float uSpec;
uniform vec3 uLightDir;
uniform vec3 uSkyAmb;
uniform vec3 uGroundAmb;
uniform vec3 uCamPos;
uniform vec3 uFogColor;
uniform float uFogDensity;
out vec4 frag;
void main() {
    vec3 n = normalize(vN);
    vec3 v = normalize(uCamPos - vW);
    vec3 l = normalize(uLightDir);
    float diff = max(dot(n, l), 0.0);
    vec3 h = normalize(l + v);
    float sp = pow(max(dot(n, h), 0.0), 40.0) * uSpec;
    vec3 amb = mix(uGroundAmb, uSkyAmb, n.y * 0.5 + 0.5);
    float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0);
    vec3 c = uColor * (amb + diff * vec3(1.05, 1.0, 0.92)) + sp * vec3(1.0, 0.97, 0.9) + rim * uSkyAmb * 0.45 + uEmissive;
    float fog = 1.0 - exp(-length(uCamPos - vW) * uFogDensity);
    c = mix(c, uFogColor, clamp(fog, 0.0, 1.0));
    c = vec3(1.0) - exp(-c * 1.35);
    frag = vec4(c, 1.0);
}
"""

    const val SKY_VS = """#version 300 es
layout(location=0) in vec2 aPos;
uniform mat4 uInvVP;
out vec4 vDir;
void main() {
    vDir = uInvVP * vec4(aPos, 1.0, 1.0);
    gl_Position = vec4(aPos, 0.999, 1.0);
}
"""

    /** Analytic sky + Earth + Moon + stars, ray-cast per pixel (all distances in km). */
    const val SKY_FS = """#version 300 es
precision highp float;
in vec4 vDir;
uniform float uAlt;
uniform float uAtm;
uniform vec3 uSun;
uniform float uR;
uniform float uC0;
uniform vec3 uMoonPos;
uniform float uMoonR;
uniform float uTime;
out vec4 frag;

float hash(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.1, 0.2, 0.3));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}
float noise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(i), hash(i + vec3(1,0,0)), f.x), mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),
               mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x), mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z);
}
float fbm(vec3 p) {
    float a = 0.5;
    float s = 0.0;
    for (int i = 0; i < 4; i++) { s += a * noise(p); p = p * 2.07 + vec3(1.7, 9.2, 3.1); a *= 0.5; }
    return s;
}

void main() {
    vec3 d = normalize(vDir.xyz / vDir.w);
    float atm = uAtm;
    float dip = acos(clamp(uR / (uR + uAlt), -1.0, 1.0));
    float el = asin(clamp(d.y, -1.0, 1.0)) + dip;
    vec3 zen = mix(vec3(0.0, 0.0, 0.012), vec3(0.14, 0.36, 0.86), atm);
    vec3 hor = mix(vec3(0.02, 0.03, 0.07), vec3(0.72, 0.84, 1.0), pow(atm, 0.45));
    vec3 col = mix(hor, zen, clamp(pow(max(el, 0.0) / 1.45, 0.42), 0.0, 1.0));

    float starVis = clamp(1.0 - atm * 5.0, 0.0, 1.0);
    if (starVis > 0.0) {
        vec3 sp = d * 260.0;
        vec3 cell = floor(sp);
        float h = hash(cell);
        if (h > 0.982) {
            vec3 c = cell + 0.5 + (vec3(hash(cell + 1.3), hash(cell + 2.7), hash(cell + 4.1)) - 0.5) * 0.6;
            float b = smoothstep(0.2, 0.0, length(sp - c)) * (h - 0.982) / 0.018;
            float tw = 0.8 + 0.2 * sin(uTime * 3.0 + h * 400.0);
            vec3 tint = mix(vec3(1.0, 0.85, 0.7), vec3(0.75, 0.85, 1.0), hash(cell + 7.7));
            col += tint * b * tw * starVis * 1.8;
        }
        float mw = smoothstep(0.35, 0.0, abs(dot(d, normalize(vec3(0.3, 0.6, 0.74))))) * fbm(d * 6.0);
        col += vec3(0.25, 0.22, 0.35) * mw * starVis * 0.35;
    }

    float sd = max(dot(d, uSun), 0.0);
    col += vec3(1.0, 0.92, 0.75) * (pow(sd, 900.0) * 8.0 + pow(sd, 60.0) * 0.18 * (0.25 + atm));

    float tBest = 1e30;
    // Moon
    vec3 oc = -uMoonPos;
    float b = dot(d, oc);
    float c = dot(oc, oc) - uMoonR * uMoonR;
    float disc = b * b - c;
    if (disc > 0.0 && b < 0.0) {
        float t = -b - sqrt(disc);
        vec3 n = normalize(d * t - uMoonPos);
        float cr = fbm(n * 7.0);
        float mare = smoothstep(0.48, 0.62, fbm(n * 2.2 + 3.0));
        vec3 mc = mix(vec3(0.78, 0.77, 0.74), vec3(0.40, 0.40, 0.43), mare) * (0.7 + 0.6 * cr);
        float lit = max(dot(n, uSun), 0.0);
        vec3 moonCol = mc * (lit * 1.25 + 0.025);
        col = mix(moonCol, col, atm * 0.6);
        tBest = t;
    }
    // Earth (stable intersection: uC0 = h*(2R+h) computed in double on the CPU)
    float be = d.y * (uR + uAlt);
    float de = be * be - uC0;
    if (de > 0.0 && be < 0.0) {
        float t = uC0 / (-be + sqrt(de));
        if (t < tBest) {
            vec3 hp = vec3(0.0, uR + uAlt, 0.0) + d * t;
            vec3 n = normalize(hp);
            float cont = fbm(n * 3.0 + vec3(0.3, 0.0, 0.7)) + 0.25 * smoothstep(0.990, 1.0, n.y);
            float land = smoothstep(0.50, 0.53, cont);
            vec3 ocean = vec3(0.015, 0.08, 0.24);
            float dry = fbm(n * 8.0 + 5.0);
            vec3 landC = mix(vec3(0.12, 0.30, 0.09), vec3(0.47, 0.39, 0.22), smoothstep(0.45, 0.7, dry));
            landC = mix(landC, vec3(0.95), smoothstep(0.82, 0.92, abs(n.z)));
            vec3 surf = mix(ocean, landC, land);
            float near = exp(-t / 25.0);
            if (near > 0.01) {
                vec2 q = hp.xz * 7.0;
                float fld = noise(vec3(floor(q), 0.0));
                float road = smoothstep(0.03, 0.0, abs(fract(q.x * 0.25) - 0.5)) * 0.3;
                surf = mix(surf, surf * (0.7 + 0.6 * fld) + road, near * land);
            }
            float cl = smoothstep(0.55, 0.78, fbm(n * 10.0 + vec3(uTime * 0.002, 0.0, 0.0)));
            surf = mix(surf, vec3(0.95), cl * 0.85 * (1.0 - near));
            float lit = max(dot(n, uSun), 0.0) * 0.95 + 0.05;
            surf *= lit * 1.25;
            float haze = 1.0 - exp(-t * 0.035 * (atm + 0.03));
            vec3 hazeCol = mix(vec3(0.35, 0.55, 0.95), hor, atm);
            col = mix(surf, hazeCol * max(lit, 0.25), clamp(haze, 0.0, 1.0));
        }
    } else if (be < 0.0) {
        float cd = sqrt(max((uR + uAlt) * (uR + uAlt) - be * be, 0.0));
        float g = exp(-(cd - uR) / 70.0);
        col += vec3(0.3, 0.55, 1.0) * g * (1.0 - atm) * 0.9;
    }
    frag = vec4(col, 1.0);
}
"""

    const val BB_VS = """#version 300 es
layout(location=0) in vec2 aCorner;
layout(location=1) in vec4 iPosSize;
layout(location=2) in vec4 iColor;
layout(location=3) in vec4 iMisc;
uniform mat4 uVP;
uniform vec3 uRight;
uniform vec3 uUp;
out vec2 vUV;
out vec4 vColor;
out vec2 vKS;
void main() {
    float c = cos(iMisc.z);
    float s = sin(iMisc.z);
    vec2 cr = vec2(aCorner.x * c - aCorner.y * s, aCorner.x * s + aCorner.y * c);
    vec3 p = iPosSize.xyz + (uRight * cr.x + uUp * cr.y) * iPosSize.w;
    gl_Position = uVP * vec4(p, 1.0);
    vUV = aCorner;
    vColor = iColor;
    vKS = iMisc.xy;
}
"""

    const val BB_FS = """#version 300 es
precision highp float;
in vec2 vUV;
in vec4 vColor;
in vec2 vKS;
out vec4 frag;
float h2(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float n2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(h2(i), h2(i + vec2(1.0, 0.0)), f.x), mix(h2(i + vec2(0.0, 1.0)), h2(i + vec2(1.0, 1.0)), f.x), f.y);
}
void main() {
    float r = length(vUV);
    if (r > 1.0) discard;
    int kind = int(vKS.x + 0.5);
    float sd = vKS.y * 37.0;
    if (kind == 0) {
        float nz = n2(vUV * 2.5 + sd) * 0.6 + n2(vUV * 5.0 + sd * 1.7) * 0.4;
        float a = smoothstep(1.0, 0.2, r + (nz - 0.5) * 0.55);
        frag = vec4(vColor.rgb * (0.8 + 0.35 * nz) * (0.9 + 0.15 * vUV.y), vColor.a * a);
    } else if (kind == 1) {
        float a = pow(1.0 - r, 1.7);
        frag = vec4(vColor.rgb, vColor.a * a);
    } else if (kind == 2) {
        float nz = n2(vUV * 1.7 + sd) * 0.55 + n2(vUV * 3.9 + sd * 1.3) * 0.3 + n2(vUV * 8.5 + sd) * 0.15;
        float a = smoothstep(1.0, 0.3, r + (nz - 0.5) * 0.75);
        float shade = 0.72 + 0.28 * (vUV.y * 0.5 + 0.5) + 0.1 * nz;
        frag = vec4(vColor.rgb * shade, vColor.a * a);
    } else {
        float a = pow(1.0 - r, 3.5) + 0.25 * pow(1.0 - r, 1.0) * step(0.5, fract(vKS.y * 13.0));
        frag = vec4(vColor.rgb, vColor.a * a);
    }
    if (frag.a < 0.004) discard;
}
"""
}
