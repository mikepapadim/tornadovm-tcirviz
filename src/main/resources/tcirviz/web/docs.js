// tcirviz - an interactive visualiser for the TornadoVM TC compiler.
// Copyright (c) 2026, Michalis Papadimitriou. SPDX-License-Identifier: Apache-2.0
//
// What each stage, pass and opcode does: shown next to the IR so the tool teaches as it shows.

export const STAGES = {
  java: {
    title: 'Java source',
    what: 'The kernel as you wrote it. TornadoVM compiles the method body, not the class: every task is a static method whose parameters are primitives or TornadoVM types (FloatArray, KernelContext, ...).',
  },
  bytecode: {
    title: 'Bytecode',
    what: 'What javac produced: a stack machine. Each instruction has a bci (bytecode index); the line-number table ties it to the Java line. TC reads this directly from the class file, without a JVM compiler.',
  },
  input: {
    title: 'TC IR (frontend)',
    what: 'Bytecode becomes SSA: the operand stack and locals turn into named values (%i, %x) built on the fly (Braun et al. 2013). Loops and merges carry values as block arguments instead of phi nodes. Every instruction remembers its Java line and bci.',
  },
  inline: {
    title: 'Inline',
    what: 'Calls to helper methods (yours, or Java bodies of TornadoVM API methods) are replaced by the callee\'s IR. After this, the kernel is one function.',
  },
  'local-collections': {
    title: 'Local collections',
    what: 'Vector collections created inside the kernel become private arrays sized at their construction.',
  },
  specialise: {
    title: 'Specialise',
    what: 'Facts about the actual arguments (array lengths, constants) fold into the IR, so loop bounds and sizes become constants.',
  },
  tiles: { title: 'Tiles', what: 'TileContext operations get their shapes and types checked and lowered.' },
  atomics: { title: 'Atomics', what: 'AtomicInteger counters get their slots in the atomics region the runtime passes to every kernel.' },
  reduce: { title: 'Reductions', what: '@Reduce loops become a tree reduction in local memory plus a final combine.' },
  batch: { title: 'Batch offset', what: 'In batched execution each launch sees one chunk: indices are shifted by the chunk offset.' },
  parallel: {
    title: 'Parallel loops',
    what: 'Each @Parallel loop becomes a grid-stride loop: the induction variable starts at the thread\'s global id and steps by the grid size. This is where the Java loop turns into GPU threads.',
  },
  optimize: {
    title: 'Optimise',
    what: 'The standard pass pipeline, before the ABI: canonicalise, simplify the CFG, remove dead code, unroll small loops, value numbering, loop-invariant code motion, math peepholes. Expand it to see each pass.',
  },
  'local-arrays': { title: 'Local arrays', what: 'Private and local (shared) arrays get their final, constant sizes.' },
  legality: {
    title: 'Legality',
    what: 'Checks the kernel against the target: double precision, half precision, vector widths, tensor cores. A kernel the device cannot run is refused here with a TC-E4xxx diagnostic, before any code is generated.',
  },
  abi: {
    title: 'ABI lowering',
    what: 'Java-level arrays and fields become raw memory: an array is a pointer to its header, its data starts after the header, and a.get(i) becomes a load from data + i * size. Object reference fields are decoded once per object.',
  },
  'optimize.post-abi': {
    title: 'Optimise (post-ABI)',
    what: 'The same passes again on the pointer-level IR: address arithmetic now visible to value numbering and code motion.',
  },
  structure: {
    title: 'Structured IR',
    what: 'SSA is destroyed (block arguments become copies) and the control-flow graph is turned back into if/while/break (Ramsey 2022, "Beyond Relooper"). This is still target-neutral.',
  },
  emit: {
    title: 'Target source',
    what: 'The structured kernel printed in the target language (CUDA C++, OpenCL C, Metal). Comments give the Java line each statement came from.',
  },
};

export const PASSES = {
  canonicalize: 'Rewrites instructions into one canonical form and folds constants (x + 0 -> x, 2 * 3 -> 6, constants to the right).',
  'cfg-simplify': 'Simplifies the control-flow graph: merges straight-line blocks, removes empty blocks and branches on constants.',
  dce: 'Dead-code elimination: removes instructions whose results nobody uses and that have no side effect.',
  unroll: 'Fully unrolls loops with a small constant trip count.',
  gvn: 'Global value numbering: an expression computed twice with the same operands is computed once and reused.',
  licm: 'Loop-invariant code motion: an expression that does not change inside a loop moves before the loop.',
  'math-peepholes': 'Math rewrites (tornado.enable.mathOptimizations, on by default): 1 / sqrt(x) -> rsqrt(x).',
};

// Mnemonic prefixes of TC IR, explained on hover.
export const OPCODES = [
  ['const', 'A constant.'],
  ['add', 'Integer addition (wraps like Java).'], ['sub', 'Integer subtraction.'], ['mul', 'Integer multiplication.'],
  ['sdiv', 'Signed division (Java /).'], ['srem', 'Signed remainder (Java %).'],
  ['fadd', 'Floating-point addition.'], ['fsub', 'Floating-point subtraction.'], ['fmul', 'Floating-point multiplication.'], ['fdiv', 'Floating-point division.'],
  ['shl', 'Shift left (the count is masked like Java).'], ['ashr', 'Arithmetic shift right (Java >>).'], ['lshr', 'Logical shift right (Java >>>).'],
  ['and', 'Bitwise and.'], ['or', 'Bitwise or.'], ['xor', 'Bitwise xor.'],
  ['icmp', 'Integer comparison: slt/sle/sgt/sge are signed, eq/ne equality. The result is i1 (a boolean).'],
  ['fcmp', 'Floating-point comparison; o* comparisons are false when an operand is NaN, u* ones true.'],
  ['select', 'c ? a : b without a branch.'],
  ['sext', 'Sign-extend to a wider integer.'], ['zext', 'Zero-extend to a wider integer.'], ['trunc', 'Truncate to a narrower integer.'],
  ['fptosi', 'Float to signed integer, saturating like Java.'], ['sitofp', 'Signed integer to float.'], ['fpext', 'Float to double.'], ['fptrunc', 'Double to float.'], ['bitcast', 'Reinterpret the bits.'],
  ['array.load', 'Read an element of a Java array (before ABI lowering).'], ['array.store', 'Write an element of a Java array.'], ['array.length', 'The length of an array.'],
  ['field.load', 'Read a field of an object argument.'], ['field.store', 'Write a field of an object argument.'],
  ['ptr.offset', 'Pointer plus a byte offset (after ABI lowering).'], ['load', 'Read memory at an address.'], ['store', 'Write memory at an address.'],
  ['alloca', 'A private or local (shared) array.'],
  ['call', 'A call; inlining removes them before code generation.'],
  ['br', 'Jump to a block, passing block arguments.'], ['cond_br', 'Branch on an i1: first target when true.'], ['switch', 'Multi-way branch.'],
  ['ret', 'Return.'], ['unreachable', 'Control never gets here.'],
  ['tornado.thread_id.global', 'The thread\'s global index in one dimension (CUDA blockIdx * blockDim + threadIdx; OpenCL get_global_id).'],
  ['tornado.thread_id.local', 'The thread\'s index inside its work-group (CUDA threadIdx; OpenCL get_local_id).'],
  ['tornado.global_size', 'The total number of threads in one dimension.'],
  ['tornado.group_id', 'The work-group (block) index.'],
  ['tornado.barrier', 'All threads of the work-group wait here.'],
  ['tornado.atomic', 'An atomic read-modify-write.'],
  ['tornado.vector', 'A lane operation on a short vector (float4, half8, ...).'],
  // TileContext (CUDA Tile): longer names first, since a prefix also matches the names it starts.
  ['tile.view_strided', 'A tensor view over part of an array, with a row stride (TileContext.viewStrided).'],
  ['tile.view', 'A tensor view: an array seen as a 1-D or 2-D tensor of the given extents (TileContext.view).'],
  ['tile.partition', 'Cut a view into tiles of a fixed shape; tile i of the partition is what load(i) reads.'],
  ['tile.bid', 'This tile block\'s index in one dimension (TileContext.bidX/Y/Z; CUDA Tile ct::bid()).'],
  ['tile.num_blocks', 'The number of tile blocks in one dimension (TileContext.numBlocksX/Y/Z).'],
  ['tile.load_masked', 'Load a tile; elements past the end of the view read as zero.'],
  ['tile.store_masked', 'Store a tile; elements past the end of the view are not written.'],
  ['tile.atomic_load', 'Load a tile with an atomic read per element.'],
  ['tile.atomic_store', 'Store a tile with an atomic write per element.'],
  ['tile.atomic', 'An atomic read-modify-write of a whole tile (add, sub, min, max, and, or, xor, exchange).'],
  ['tile.load', 'Load the tile at a block index of a partition view.'],
  ['tile.store', 'Store a tile at a block index of a partition view.'],
  ['tile.binary', 'Elementwise binary operation on tiles; extents of 1 broadcast.'],
  ['tile.unary', 'Elementwise unary operation or math function on a tile.'],
  ['tile.compare_scalar', 'Elementwise comparison of a tile with a scalar: a boolean tile.'],
  ['tile.compare', 'Elementwise comparison of two tiles: a boolean tile.'],
  ['tile.reduce', 'Reduce a tile along an axis (sum, max, min, ...); the reduced axis keeps extent 1.'],
  ['tile.mma', 'Matrix multiply-accumulate a·b + acc on tiles: tensor cores where the target has them.'],
  ['tile.fma', 'Elementwise fused multiply-add a·b + c.'],
  ['tile.scale', 'Multiply every element of a tile by a scalar.'],
  ['tile.select', 'Elementwise choice: whenTrue where the predicate tile is true, else whenFalse.'],
  ['tile.cast', 'Convert a tile to another element type.'],
  ['tile.bitcast', 'Reinterpret a tile\'s bits as another element type of the same width.'],
  ['tile.broadcast', 'Stretch the extents of 1 of a tile to a larger shape.'],
  ['tile.reshape', 'The same elements with another shape.'],
  ['tile.extract', 'A sub-tile at a block offset, without loading again.'],
  ['tile.concat', 'Join two tiles along an axis.'],
  ['tile.zeros', 'A tile of zeros.'], ['tile.ones', 'A tile of ones.'], ['tile.full', 'A tile filled with one value.'],
  ['tile.iota', 'A tile numbered 0, 1, 2, ... in row-major order.'],
  ['tile.', 'A TileContext operation, lowered to CUDA Tile C++ (cuda_tile.h).'],
  ['math.', 'A math function, mapped to the target\'s built-in (sqrtf, native exp, ...).'],
  ['tornado.', 'A TornadoVM intrinsic: an operation with a direct meaning on the GPU.'],
];

export function opcodeHelp(text) {
  const m = text.match(/=\s*([a-z_][\w.]*)/) || text.match(/^\s*([a-z_][\w.]*)/);
  if (!m) return null;
  const op = m[1];
  for (const [prefix, help] of OPCODES) {
    if (op === prefix || op.startsWith(prefix + '.') || (prefix.endsWith('.') && op.startsWith(prefix)) || op.startsWith(prefix)) {
      return { op, help };
    }
  }
  return null;
}
