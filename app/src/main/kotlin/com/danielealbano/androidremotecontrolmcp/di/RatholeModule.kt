package com.danielealbano.androidremotecontrolmcp.di

import com.danielealbano.androidremotecontrolmcp.services.tunnel.RatholeTunnelProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifies the proc filesystem root (used for orphaned rathole client detection). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProcRoot

@Module
@InstallIn(SingletonComponent::class)
object RatholeModule {
    /**
     * Provides the proc filesystem root. Injected (instead of hardcoded) so unit tests can
     * point rathole orphan-client detection at a fake proc directory.
     */
    @Provides
    @Singleton
    @ProcRoot
    fun provideProcRoot(): File = File(RatholeTunnelProvider.DEFAULT_PROC_DIR)
}
