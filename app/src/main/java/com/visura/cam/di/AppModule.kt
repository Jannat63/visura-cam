package com.visura.cam.di

import android.content.Context
import com.visura.cam.ai.SceneDetector
import com.visura.cam.camera.*
import com.visura.cam.correction.CalibrationDataStore
import com.visura.cam.correction.ColorCorrectionEngine
import com.visura.cam.utils.BatchColorFixer
import com.visura.cam.utils.ImageSaver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideCalibrationDataStore(@ApplicationContext ctx: Context) =
        CalibrationDataStore(ctx)

    @Provides @Singleton
    fun provideColorCorrectionEngine(store: CalibrationDataStore) =
        ColorCorrectionEngine(store)

    @Provides @Singleton
    fun provideCameraController(
        @ApplicationContext ctx: Context,
        engine: ColorCorrectionEngine
    ) = VisuraCameraController(ctx, engine)

    @Provides @Singleton
    fun provideMacroController(
        cam: VisuraCameraController,
        engine: ColorCorrectionEngine
    ) = MacroController(cam, engine)

    @Provides @Singleton
    fun provideNightModeController(engine: ColorCorrectionEngine) =
        NightModeController(engine)

    @Provides @Singleton
    fun provideSceneDetector(@ApplicationContext ctx: Context) =
        SceneDetector(ctx)

    @Provides @Singleton
    fun provideImageSaver(
        @ApplicationContext ctx: Context,
        engine: ColorCorrectionEngine
    ) = ImageSaver(ctx, engine)  // Full pro pipeline inside

    @Provides @Singleton
    fun provideBatchColorFixer(
        @ApplicationContext ctx: Context,
        engine: ColorCorrectionEngine
    ) = BatchColorFixer(ctx, engine)
}
