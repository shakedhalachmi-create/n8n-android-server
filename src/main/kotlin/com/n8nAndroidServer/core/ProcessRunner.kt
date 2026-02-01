package com.n8nAndroidServer.core

import java.io.File
import java.io.InputStream

/**
 * Abstraction for process execution to enable testing.
 * Production uses RealProcessRunner, tests use MockProcessRunner.
 */
interface ProcessRunner {
    /**
     * Start a new process with the given command, environment, and working directory.
     * @param command The command and arguments to execute
     * @param env Environment variables (overrides system env)
     * @param workingDir Working directory for the process
     * @return RunningProcess handle for managing the process
     */
    fun start(command: List<String>, env: Map<String, String>, workingDir: File): RunningProcess
}

/**
 * Handle to a running process.
 * Provides access to streams, PID, and lifecycle management.
 */
interface RunningProcess {
    /**
     * Get the process ID. Returns -1 if unavailable.
     */
    fun pid(): Int
    
    /**
     * Standard output stream from the process.
     */
    val inputStream: InputStream
    
    /**
     * Standard error stream from the process.
     */
    val errorStream: InputStream
    
    /**
     * Wait for the process to exit and return the exit code.
     * This is a blocking call.
     */
    fun waitFor(): Int
    
    /**
     * Check if the process is still alive.
     */
    fun isAlive(): Boolean
    
    /**
     * Attempt graceful termination (SIGTERM).
     */
    fun destroy()
    
    /**
     * Force termination (SIGKILL).
     */
    fun destroyForcibly()
}
