package com.github.plugin

import com.android.build.api.transform.Format
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformInvocation
import com.android.build.gradle.internal.pipeline.TransformManager
import com.github.plugin.asm.CustomInjectClassVisitor
import com.github.plugin.asm.ExtendClassWriter
import com.github.plugin.asm.WeaveSingleClass
import com.github.plugin.utils.TypeUtil
import com.github.plugin.utils.ZipFileUtils
import com.github.plugin.utils.eachFileRecurse
import com.github.plugin.utils.getUniqueJarName
import org.apache.commons.io.FileUtils
import org.gradle.internal.impldep.aQute.bnd.osgi.OpCodes
import org.objectweb.asm.*
import org.objectweb.asm.commons.AdviceAdapter
import java.io.*
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class AfterTransform : Transform() {
    override fun getName(): String {
        return AfterTransform::class.simpleName ?: ""
    }

    override fun getInputTypes(): MutableSet<QualifiedContent.ContentType> {
        return TransformManager.CONTENT_CLASS
    }

    override fun isIncremental(): Boolean {
        return false
    }

    override fun getScopes(): MutableSet<in QualifiedContent.Scope> {
        return TransformManager.SCOPE_FULL_PROJECT
    }


    override fun transform(transformInvocation: TransformInvocation) {
        KLogger.e(">>>>>>>>>>>>>AfterTransform")

        if (!transformInvocation.isIncremental) {
            transformInvocation.outputProvider.deleteAll()
        }


        transformInvocation.inputs.forEach { input ->


            input.directoryInputs.forEach { dirInput ->
                //处理完输入文件之后，要把输出给下一个任务,就是在：transforms\ModuleTransformKt\debug\0目录中
                val dest = transformInvocation.outputProvider.getContentLocation(dirInput.name,
                        dirInput.contentTypes,
                        dirInput.scopes,
                        Format.DIRECTORY).also(FileUtils::forceMkdir)
                dirInput.file.eachFileRecurse { file ->
                    if (TypeUtil.isMatchCondition(file.name)) {//不能只仅仅操作InjectManager.class,会奔溃，具体我还不知道为甚么
                        val outputFile = File(file.absolutePath.replace(dirInput.file.absolutePath, dest.absolutePath))
                        FileUtils.touch(outputFile)
                        val inputStream = FileInputStream(file)
                        val bytes = weaveSingleClassToByteArray2(inputStream)//需要织入代码
                        val fos = FileOutputStream(outputFile)
                        fos.write(bytes)
                        fos.close()
                        inputStream.close()
                    }
                }
            }
            input.jarInputs.forEach { jarInput ->
                val dest = transformInvocation.outputProvider.getContentLocation(
                        jarInput.file.getUniqueJarName(),
                        jarInput.contentTypes,
                        jarInput.scopes,
                        Format.JAR)
                FileUtils.copyFile(jarInput.file, dest)
                weaveJarTask(jarInput.file, dest)
            }

        }
    }


    private fun weaveJarTask(input: File, output: File) {
        //input: build\intermediates\runtime_library_classes\debug\classes.jar
        //output: build\intermediates\transforms\ModuleTransformKt\debug\0.jar
        KLogger.e("input: ${input.absolutePath}  output: ${output.absolutePath}")
        var zipOutputStream: ZipOutputStream? = null
        var zipFile: ZipFile? = null
        try {
            zipOutputStream = ZipOutputStream(BufferedOutputStream(Files.newOutputStream(output.toPath())))
            zipFile = ZipFile(input)
            val enumeration = zipFile.entries()

            while (enumeration.hasMoreElements()) {
                val zipEntry = enumeration.nextElement()
                val zipEntryName = zipEntry.name
                //jar文件里面就是class文件的了
                // com/github/plugin/usercenter/UserComponent.class
                // com/github/plugin/common/BuildConfig.class
                KLogger.e("zipEntryName:$zipEntryName")
                if (TypeUtil.isMatchCondition(zipEntryName)) {

                    val data = weaveSingleClassToByteArray2(BufferedInputStream(zipFile.getInputStream(zipEntry)))

                    val byteArrayInputStream = ByteArrayInputStream(data)

                    val newZipEntry = ZipEntry(zipEntryName)
                    ZipFileUtils.addZipEntry(zipOutputStream, newZipEntry, byteArrayInputStream)
                } else {
                    val inputStream = zipFile.getInputStream(zipEntry)
                    val newZipEntry = ZipEntry(zipEntryName)
                    ZipFileUtils.addZipEntry(zipOutputStream, newZipEntry, inputStream)
                }
            }
        } catch (e: Exception) {
        } finally {
            try {
                if (zipOutputStream != null) {
                    zipOutputStream.finish()
                    zipOutputStream.flush()
                    zipOutputStream.close()
                }
                zipFile?.close()
            } catch (e: Exception) {
                KLogger.e("close stream err!")
            }
        }
    }

    private fun weaveSingleClassToByteArray2(inputStream: InputStream): ByteArray {

        //1、解析字节码
        val classReader = ClassReader(inputStream)

        //2、修改字节码
        val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS)
        val customClassVisitor = CustomScannerInjectClassVisitor(classWriter)

        //3、开始解析字节码
        classReader.accept(customClassVisitor, ClassReader.EXPAND_FRAMES)
        return classWriter.toByteArray()
    }
}

class CustomScannerInjectClassVisitor(classVisitor: ClassVisitor) : ClassVisitor(Opcodes.ASM7, classVisitor) {
    //如果是实现了IComponent接口的话，将所有组件类收集起来，通过修改字节码的方式生成注册代码到组件管理类中
    override fun visit(version: Int, access: Int, name: String?, signature: String?, superName: String?, interfaces: Array<out String>?) {
        KLogger.e("${interfaces?.joinToString { it }}")
        if (interfaces?.contains(MATCH_CLASS) == true && name != "") ScanerCollections.add("$name")
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
        KLogger.e("name:$name     descriptor:$descriptor")

        val visitMethod = super.visitMethod(access, name, descriptor, signature, exceptions)
        if (MATCH_METHOD_INIT_COMPONENT != name) {
            return visitMethod
        }
        return CustomScannerMethod(visitMethod, access, name, descriptor)
    }


    companion object {
        const val MATCH_CLASS = "com/github/plugin/common/IComponent"
        const val MATCH_METHOD_INIT_COMPONENT = "initComponent"
    }
}

class CustomScannerMethod(methodVisitor: MethodVisitor?, access: Int, name: String?, descriptor: String?) : AdviceAdapter(Opcodes.ASM7, methodVisitor, access, name, descriptor) {

    override fun onMethodEnter() {
        KLogger.e("${ScanerCollections.size}")
        ScanerCollections.forEach { name ->
            //加载this
            mv.visitVarInsn(ALOAD, 0)
            //拿到类的成员变量
            mv.visitFieldInsn(GETFIELD, MATCH_MANAGER_CLASS, "components", "Ljava/util/List;")
            //用无参构造方法创建一个组件实例
            mv.visitTypeInsn(Opcodes.NEW, name)
            mv.visitInsn(Opcodes.DUP)
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, name, "<init>", "()V", false)
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true)
            mv.visitInsn(POP)
        }
    }

    companion object {
        const val MATCH_MANAGER_CLASS = "com.github.plugin.common.InjectManager"
    }
}