package com.walltrace.ar.ar.rendering

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

object GlUtil {

    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        if (program == 0) throw IllegalStateException("glCreateProgram failed")
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw IllegalStateException("Program link failed: $log")
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) throw IllegalStateException("glCreateShader failed")
        // Drivers require "#version" / "#extension" to start the source, so strip indentation.
        GLES30.glShaderSource(shader, source.trimIndent())
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("Shader compile failed: $log")
        }
        return shader
    }

    fun floatBuffer(capacityFloats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(capacityFloats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun floatBuffer(data: FloatArray): FloatBuffer =
        floatBuffer(data.size).apply {
            put(data)
            position(0)
        }

    fun shortBuffer(capacityShorts: Int): ShortBuffer =
        ByteBuffer.allocateDirect(capacityShorts * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

    fun uniform(program: Int, name: String): Int = GLES30.glGetUniformLocation(program, name)

    fun attribute(program: Int, name: String): Int {
        val loc = GLES30.glGetAttribLocation(program, name)
        if (loc < 0) throw IllegalStateException("Attribute $name not found")
        return loc
    }
}
