// tcirviz - an interactive visualiser for the TornadoVM TC compiler.
// Copyright (c) 2026, Michalis Papadimitriou. SPDX-License-Identifier: GPL-2.0-only
//
// Small single-line tokenisers: Java, bytecode, TC IR, structured IR and C-family target code.
// Each returns HTML; IR value names become <span class="val" data-k="n"> where n is the token's
// position, so the caller can map it to the value id the line defines or uses.

export const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

const JAVA_KW = new Set('abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try var void volatile while record yield true false null'.split(' '));
const C_KW = new Set('auto break case char const continue default do double else enum extern float for goto if inline int long register restrict return short signed sizeof static struct switch typedef union unsigned void volatile while bool half uchar ushort uint ulong char2 char3 char4 int2 int3 int4 int8 int16 float2 float3 float4 float8 float16 double2 double3 double4 double8 double16 half2 half3 half4 half8 half16 short2 short3 short4 long2 long4 true false'.split(' '));
const C_SPECIAL = /^(__global__|__device__|__shared__|__constant__|__kernel|__global|__local|__constant|__private|kernel|device|threadgroup|threadIdx|blockIdx|blockDim|gridDim|get_global_id|get_local_id|get_group_id|get_local_size|get_global_size|get_num_groups|barrier|__syncthreads|CLK_LOCAL_MEM_FENCE|CLK_GLOBAL_MEM_FENCE|atomicAdd|atomic_add|atom_add)$/;
const IR_TYPES = /^(i1|i8|i16|i32|i64|f16|bf16|f32|f64|void|ptr|vec|obj|tile|tileview)$/;

function tokenize(text, rules) {
  let out = '';
  let i = 0;
  outer: while (i < text.length) {
    for (const [re, fn] of rules) {
      re.lastIndex = i;
      const m = re.exec(text);
      if (m && m.index === i && m[0].length > 0) {
        out += fn(m[0], m);
        i += m[0].length;
        continue outer;
      }
    }
    out += esc(text[i]);
    i++;
  }
  return out;
}

const span = (cls) => (s) => `<span class="${cls}">${esc(s)}</span>`;

export function java(text) {
  return tokenize(text, [
    [/\/\/.*$/y, span('cm')],
    [/\/\*.*?(\*\/|$)/y, span('cm')],
    [/"(?:[^"\\]|\\.)*"?/y, span('str')],
    [/'(?:[^'\\]|\\.)*'?/y, span('str')],
    [/@\w+/y, span('ann')],
    [/\b\d[\d_]*(\.\d+)?([eE][+-]?\d+)?[fFdDlL]?\b/y, span('num')],
    [/[A-Za-z_$][\w$]*/y, (w) => JAVA_KW.has(w) ? span('kw')(w) : /^[A-Z]/.test(w) ? span('ty')(w) : esc(w)],
  ]);
}

export function bytecode(text) {
  return tokenize(text, [
    [/\/\/.*$/y, span('cm')],
    [/^\s*\d+:/y, span('bci')],
    [/(?<=:\s+)[a-z_0-9]+/y, span('op')],
    [/[A-Za-z_$][\w$/]*\.[\w$<>]+/y, span('ty')],
    [/-?\d+/y, span('num')],
  ]);
}

export function ir(text) {
  let k = 0;
  return tokenize(text, [
    [/loc\([^)]*\)/y, span('cm')],
    [/"(?:[^"\\]|\\.)*"/y, span('str')],
    [/%[\w.$?]+/y, (s) => `<span class="val" data-k="${k++}">${esc(s)}</span>`],
    [/\^[\w.$?]+/y, span('blk')],
    [/@[\w.$<>]+/y, span('fn')],
    [/\b(kernel|func|device)\b/y, span('kw')],
    [/(?<==\s*)[a-z_][\w.]*/y, span('op')],
    [/^(\s*)(br|cond_br|switch|ret|unreachable|array\.store|field\.store|store|tornado\.[\w.]+|math\.[\w.]+|call)\b/y, (s, m) => esc(m[1]) + span('op')(m[2])],
    [/\b[a-z]\w*(?=\s*=)/y, span('attr')],
    [/\b(i1|i8|i16|i32|i64|f16|bf16|f32|f64|void|ptr|vec|obj|tile|tileview)\b/y, span('ty')],
    [/-?\b\d+(\.\d+)?([eE][+-]?\d+)?\b|\bnan\b|-?\binf\b/y, span('num')],
  ]);
}

export function structured(text) {
  return tokenize(text, [
    [/\b(kernel|func|device|loop|block|if|else|break|continue|return|switch|case|default|unreachable)\b/y, span('kw')],
    [/\b(i1|i8|i16|i32|i64|f16|bf16|f32|f64|void|ptr|vec|obj|tile|tileview|global|shared|private|constant)\b/y, span('ty')],
    [/\b(load|store|ptr\.offset|sext|zext|trunc|fptosi|sitofp|tornado\.[\w.]+|math\.[\w.]+)\b/y, span('op')],
    [/-?\b\d+(\.\d+)?\b/y, span('num')],
  ]);
}

export function target(text) {
  return tokenize(text, [
    [/\/\/.*$/y, span('cm')],
    [/\/\*.*?(\*\/|$)/y, span('cm')],
    [/^\s*#.*$/y, span('pp')],
    [/"(?:[^"\\]|\\.)*"/y, span('str')],
    [/\b0x[\da-fA-F]+[uUlL]*\b|\b\d+(\.\d+)?([eE][+-]?\d+)?[fFuUlL]*\b/y, span('num')],
    [/[A-Za-z_]\w*/y, (w) => C_SPECIAL.test(w) ? span('sp')(w) : C_KW.has(w) ? span('kw')(w) : esc(w)],
  ]);
}

export function forKind(kind) {
  return { java, bytecode, ir, structured, source: target }[kind] || ((t) => esc(t));
}
