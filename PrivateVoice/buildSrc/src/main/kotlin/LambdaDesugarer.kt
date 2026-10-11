import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Minimal Java 8 lambda desugarer for the dx-based build.
 *
 * dx (the only dexer available without Google's Maven) passes `invokedynamic`
 * through as `invoke-custom`, but ART has no `LambdaMetafactory`, so any
 * lambda created that way would crash at runtime. This rewrites every
 * LambdaMetafactory call site the same way D8 does:
 *
 *  - each call site gets a synthetic final class implementing the functional
 *    interface, holding captured values in fields;
 *  - the `invokedynamic` is replaced by `invokestatic Lambda.create(captures)`;
 *  - private implementation methods are reached through a static accessor
 *    added to the owner class (package-private, or public in interfaces).
 *
 * Any other bootstrap method fails the build loudly rather than producing an
 * APK that crashes later.
 */
object LambdaDesugarer {
    private const val LMF = "java/lang/invoke/LambdaMetafactory"
    private const val FLAG_SERIALIZABLE = 1
    private const val FLAG_MARKERS = 2
    private const val FLAG_BRIDGES = 4

    data class Result(val classes: Int, val lambdaSites: Int)

    /** Desugars all classes of [inputs] (jars or class directories) into a single output jar. */
    fun desugar(inputs: Collection<File>, output: File): Result {
        output.parentFile.mkdirs()
        var classes = 0
        var sites = 0
        val seen = HashSet<String>()
        ZipOutputStream(output.outputStream().buffered()).use { zos ->
            fun emit(name: String, bytes: ByteArray) {
                if (!seen.add(name)) return
                zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
            }
            fun handle(name: String, bytes: ByteArray) {
                if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.endsWith("module-info.class")) return
                classes++
                val out = transform(bytes)
                sites += out.sites
                emit(name, out.bytes)
                out.extra.forEach { (n, b) -> emit("$n.class", b) }
            }
            for (input in inputs) {
                if (!input.exists()) continue
                if (input.isDirectory) {
                    input.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach {
                        handle(it.relativeTo(input).invariantSeparatorsPath, it.readBytes())
                    }
                } else {
                    ZipFile(input).use { zf ->
                        zf.entries().toList().filter { !it.isDirectory }.sortedBy { it.name }.forEach { e ->
                            handle(e.name, zf.getInputStream(e).readBytes())
                        }
                    }
                }
            }
        }
        return Result(classes, sites)
    }

    class Transformed(val bytes: ByteArray, val extra: List<Pair<String, ByteArray>>, val sites: Int)

    fun transform(bytes: ByteArray): Transformed {
        val cr = ClassReader(bytes)
        val cn = ClassNode()
        cr.accept(cn, 0)
        val extra = ArrayList<Pair<String, ByteArray>>()
        var counter = 0
        val ownerIsInterface = (cn.access and ACC_INTERFACE) != 0
        val accessors = HashMap<String, MethodNode>()
        val newMethods = ArrayList<MethodNode>()

        for (m in cn.methods.toList()) {
            val insns = m.instructions ?: continue
            for (insn in insns.toArray()) {
                if (insn !is InvokeDynamicInsnNode) continue
                val bsm = insn.bsm
                require(bsm.owner == LMF) {
                    "Unsupported invokedynamic bootstrap ${bsm.owner}.${bsm.name} in ${cn.name}.${m.name}"
                }
                val samType = insn.bsmArgs[0] as Type
                var impl = insn.bsmArgs[1] as Handle
                val instantiated = insn.bsmArgs[2] as Type
                val markers = ArrayList<String>()
                val bridges = ArrayList<Type>()
                if (bsm.name == "altMetafactory") {
                    val flags = insn.bsmArgs[3] as Int
                    var i = 4
                    if (flags and FLAG_MARKERS != 0) {
                        val n = insn.bsmArgs[i++] as Int
                        repeat(n) { markers += (insn.bsmArgs[i++] as Type).internalName }
                    }
                    if (flags and FLAG_BRIDGES != 0) {
                        val n = insn.bsmArgs[i++] as Int
                        repeat(n) { bridges += insn.bsmArgs[i++] as Type }
                    }
                    if (flags and FLAG_SERIALIZABLE != 0 && "java/io/Serializable" !in markers) markers += "java/io/Serializable"
                } else require(bsm.name == "metafactory") { "Unsupported LambdaMetafactory method ${bsm.name}" }

                // Private targets in this class need a static accessor the lambda class can call.
                if (impl.owner == cn.name) {
                    val target = cn.methods.firstOrNull { it.name == impl.name && it.desc == impl.desc }
                    val isPrivate = target != null && (target.access and ACC_PRIVATE) != 0
                    if (isPrivate || impl.tag == H_INVOKESPECIAL) {
                        val key = "${impl.tag}:${impl.name}:${impl.desc}"
                        val acc = accessors.getOrPut(key) {
                            makeAccessor(cn, impl, ownerIsInterface, accessors.size).also { newMethods += it }
                        }
                        impl = Handle(H_INVOKESTATIC, cn.name, acc.name, acc.desc, ownerIsInterface)
                    }
                }

                val lambdaName = "${cn.name}\$\$Lambda\$Desugar\$${counter++}"
                val factoryDesc = insn.desc
                val iface = Type.getReturnType(factoryDesc).internalName
                extra += lambdaName to buildLambdaClass(
                    lambdaName, iface, markers, insn.name, samType, bridges, impl, instantiated, factoryDesc, cn.version,
                )
                insns.set(insn, MethodInsnNode(INVOKESTATIC, lambdaName, "create", factoryDesc, false))
            }
        }
        cn.methods.addAll(newMethods)
        if (extra.isEmpty()) return Transformed(bytes, emptyList(), 0)
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cn.accept(cw)
        return Transformed(cw.toByteArray(), extra, extra.size)
    }

    private fun makeAccessor(cn: ClassNode, impl: Handle, itf: Boolean, index: Int): MethodNode {
        val implArgs = Type.getArgumentTypes(impl.desc)
        val access = ACC_STATIC or ACC_SYNTHETIC or (if (itf) ACC_PUBLIC else 0)
        val (desc, body) = when (impl.tag) {
            H_INVOKESTATIC -> impl.desc to { mn: MethodNode ->
                loadArgs(mn, implArgs, 0)
                mn.instructions.add(MethodInsnNode(INVOKESTATIC, cn.name, impl.name, impl.desc, impl.isInterface))
            }
            H_NEWINVOKESPECIAL -> Type.getMethodDescriptor(Type.getObjectType(cn.name), *implArgs) to { mn: MethodNode ->
                mn.instructions.add(TypeInsnNode(NEW, cn.name))
                mn.instructions.add(InsnNode(DUP))
                loadArgs(mn, implArgs, 0)
                mn.instructions.add(MethodInsnNode(INVOKESPECIAL, cn.name, "<init>", impl.desc, false))
            }
            else -> Type.getMethodDescriptor(Type.getReturnType(impl.desc), Type.getObjectType(cn.name), *implArgs) to { mn: MethodNode ->
                mn.instructions.add(VarInsnNode(ALOAD, 0))
                loadArgs(mn, implArgs, 1)
                mn.instructions.add(MethodInsnNode(INVOKESPECIAL, cn.name, impl.name, impl.desc, impl.isInterface))
            }
        }
        val mn = MethodNode(access, "access\$lambda\$desugar\$$index", desc, null, null)
        body(mn)
        mn.instructions.add(InsnNode(Type.getReturnType(desc).getOpcode(IRETURN)))
        return mn
    }

    private fun loadArgs(mn: MethodNode, args: Array<Type>, startSlot: Int) {
        var slot = startSlot
        for (t in args) {
            mn.instructions.add(VarInsnNode(t.getOpcode(ILOAD), slot)); slot += t.size
        }
    }

    private fun buildLambdaClass(
        name: String, iface: String, markers: List<String>, samName: String, samType: Type, bridges: List<Type>,
        impl: Handle, instantiated: Type, factoryDesc: String, version: Int,
    ): ByteArray {
        val captured = Type.getArgumentTypes(factoryDesc)
        val cn = ClassNode()
        cn.version = maxOf(version and 0xFFFF, V1_8).coerceAtMost(V1_8)
        cn.access = ACC_FINAL or ACC_SYNTHETIC or ACC_SUPER
        cn.name = name
        cn.superName = "java/lang/Object"
        cn.interfaces = (listOf(iface) + markers).distinct().toMutableList()

        captured.forEachIndexed { i, t ->
            cn.fields.add(org.objectweb.asm.tree.FieldNode(ACC_PRIVATE or ACC_FINAL, "f$i", t.descriptor, null, null))
        }
        // constructor
        val ctorDesc = Type.getMethodDescriptor(Type.VOID_TYPE, *captured)
        MethodNode(ACC_PRIVATE, "<init>", ctorDesc, null, null).also { mn ->
            mn.instructions.add(VarInsnNode(ALOAD, 0))
            mn.instructions.add(MethodInsnNode(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false))
            var slot = 1
            captured.forEachIndexed { i, t ->
                mn.instructions.add(VarInsnNode(ALOAD, 0))
                mn.instructions.add(VarInsnNode(t.getOpcode(ILOAD), slot)); slot += t.size
                mn.instructions.add(FieldInsnNode(PUTFIELD, name, "f$i", t.descriptor))
            }
            mn.instructions.add(InsnNode(RETURN))
            cn.methods.add(mn)
        }
        // factory
        MethodNode(ACC_STATIC or ACC_SYNTHETIC, "create", factoryDesc, null, null).also { mn ->
            mn.instructions.add(TypeInsnNode(NEW, name))
            mn.instructions.add(InsnNode(DUP))
            loadArgs(mn, captured, 0)
            mn.instructions.add(MethodInsnNode(INVOKESPECIAL, name, "<init>", ctorDesc, false))
            mn.instructions.add(InsnNode(ARETURN))
            cn.methods.add(mn)
        }
        // SAM + bridges
        val descs = (listOf(samType) + bridges).map { it.descriptor }.distinct()
        for (d in descs) cn.methods.add(samMethod(name, samName, Type.getMethodType(d), impl, instantiated, captured))

        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cn.accept(cw)
        return cw.toByteArray()
    }

    private fun samMethod(
        owner: String, samName: String, sam: Type, impl: Handle, instantiated: Type, captured: Array<Type>,
    ): MethodNode {
        val mn = MethodNode(ACC_PUBLIC, samName, sam.descriptor, null, null)
        val il = mn.instructions
        val implArgs = Type.getArgumentTypes(impl.desc)
        val implReturn = Type.getReturnType(impl.desc)
        val receiverTaken = impl.tag == H_INVOKEVIRTUAL || impl.tag == H_INVOKEINTERFACE || impl.tag == H_INVOKESPECIAL
        // full list of parameter types the implementation expects, receiver first
        val targetTypes = ArrayList<Type>()
        if (receiverTaken) targetTypes += Type.getObjectType(impl.owner)
        targetTypes.addAll(implArgs)

        if (impl.tag == H_NEWINVOKESPECIAL) {
            il.add(TypeInsnNode(NEW, impl.owner)); il.add(InsnNode(DUP))
        }
        var t = 0
        captured.forEachIndexed { i, ct ->
            il.add(VarInsnNode(ALOAD, 0))
            il.add(FieldInsnNode(GETFIELD, owner, "f$i", ct.descriptor))
            convert(il, ct, targetTypes[t++])
        }
        val samArgs = sam.argumentTypes
        val instArgs = instantiated.argumentTypes
        var slot = 1
        samArgs.forEachIndexed { i, st ->
            il.add(VarInsnNode(st.getOpcode(ILOAD), slot)); slot += st.size
            val via = instArgs.getOrNull(i) ?: st
            val bothRef = !isPrimitive(st) && !isPrimitive(via)
            if (bothRef && via != st && via.descriptor != "Ljava/lang/Object;") {
                il.add(TypeInsnNode(CHECKCAST, via.internalName))
            }
            convert(il, if (bothRef) via else st, targetTypes[t++])
        }
        require(t == targetTypes.size) { "Lambda arity mismatch for ${impl.owner}.${impl.name}${impl.desc}" }
        when (impl.tag) {
            H_INVOKESTATIC -> il.add(MethodInsnNode(INVOKESTATIC, impl.owner, impl.name, impl.desc, impl.isInterface))
            H_INVOKEVIRTUAL -> il.add(MethodInsnNode(INVOKEVIRTUAL, impl.owner, impl.name, impl.desc, false))
            H_INVOKEINTERFACE -> il.add(MethodInsnNode(INVOKEINTERFACE, impl.owner, impl.name, impl.desc, true))
            H_NEWINVOKESPECIAL -> il.add(MethodInsnNode(INVOKESPECIAL, impl.owner, "<init>", impl.desc, false))
            else -> error("Unexpected handle tag ${impl.tag} for ${impl.owner}.${impl.name}")
        }
        val produced = if (impl.tag == H_NEWINVOKESPECIAL) Type.getObjectType(impl.owner) else implReturn
        val wanted = sam.returnType
        if (wanted.sort == Type.VOID) {
            when (produced.size) { 1 -> il.add(InsnNode(POP)); 2 -> il.add(InsnNode(POP2)) }
        } else {
            convert(il, produced, wanted)
        }
        il.add(InsnNode(wanted.getOpcode(IRETURN)))
        return mn
    }

    private val boxes = mapOf(
        Type.BOOLEAN to ("java/lang/Boolean" to "booleanValue"),
        Type.BYTE to ("java/lang/Byte" to "byteValue"),
        Type.CHAR to ("java/lang/Character" to "charValue"),
        Type.SHORT to ("java/lang/Short" to "shortValue"),
        Type.INT to ("java/lang/Integer" to "intValue"),
        Type.LONG to ("java/lang/Long" to "longValue"),
        Type.FLOAT to ("java/lang/Float" to "floatValue"),
        Type.DOUBLE to ("java/lang/Double" to "doubleValue"),
    )

    private fun isPrimitive(t: Type) = t.sort in Type.BOOLEAN..Type.DOUBLE

    /** Emits the conversion (cast, box, unbox, widen) from [from] to [to] for the value on the stack. */
    private fun convert(il: org.objectweb.asm.tree.InsnList, from: Type, to: Type) {
        if (from == to) return
        val fp = isPrimitive(from)
        val tp = isPrimitive(to)
        when {
            fp && tp -> widen(il, from, to)
            fp && !tp -> {
                val (box, _) = boxes.getValue(from.sort)
                il.add(MethodInsnNode(INVOKESTATIC, box, "valueOf", "(${from.descriptor})L$box;", false))
                if (to.internalName != box && to.internalName != "java/lang/Object") il.add(TypeInsnNode(CHECKCAST, to.internalName))
            }
            !fp && tp -> {
                val (box, unbox) = boxes.getValue(to.sort)
                val source = if (from.sort == Type.OBJECT) from.internalName else ""
                if (source == box) {
                    il.add(MethodInsnNode(INVOKEVIRTUAL, box, unbox, "()${to.descriptor}", false))
                } else if (to.sort in listOf(Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE, Type.SHORT, Type.BYTE) && source == "java/lang/Number") {
                    il.add(MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", unbox, "()${to.descriptor}", false))
                } else {
                    il.add(TypeInsnNode(CHECKCAST, box))
                    il.add(MethodInsnNode(INVOKEVIRTUAL, box, unbox, "()${to.descriptor}", false))
                }
            }
            else -> {
                if (to.descriptor != "Ljava/lang/Object;") {
                    il.add(TypeInsnNode(CHECKCAST, to.internalName))
                }
            }
        }
    }

    private fun widen(il: org.objectweb.asm.tree.InsnList, from: Type, to: Type) {
        val f = when (from.sort) { Type.LONG -> 'J'; Type.FLOAT -> 'F'; Type.DOUBLE -> 'D'; else -> 'I' }
        val t = when (to.sort) { Type.LONG -> 'J'; Type.FLOAT -> 'F'; Type.DOUBLE -> 'D'; else -> 'I' }
        val op = when ("$f$t") {
            "IJ" -> I2L; "IF" -> I2F; "ID" -> I2D; "JF" -> L2F; "JD" -> L2D; "FD" -> F2D
            "II", "JJ", "FF", "DD" -> return
            else -> error("Unsupported primitive conversion $from -> $to")
        }
        il.add(InsnNode(op))
    }

    /** Counts remaining invokedynamic instructions in [jar] (used to verify the output). */
    fun countIndy(jar: File): Int {
        var n = 0
        ZipFile(jar).use { zf ->
            for (e in zf.entries()) {
                if (!e.name.endsWith(".class")) continue
                val cn = ClassNode()
                ClassReader(zf.getInputStream(e).readBytes()).accept(cn, ClassReader.SKIP_DEBUG)
                for (m in cn.methods) m.instructions?.forEach { if (it is InvokeDynamicInsnNode) n++ }
            }
        }
        return n
    }

    @Suppress("unused") private val keepOpcodes = Opcodes.ASM9
}
