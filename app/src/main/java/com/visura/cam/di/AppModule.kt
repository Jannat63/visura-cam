package com.visura.cam.di

import android.content.Context
import com.visura.cam.camera.CameraManager
import com.visura.cam.correction.CalibrationDataStore
import com.visura.cam.correction.ColorCorrectionEngine
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
    fun provideCameraManager(@ApplicationContext ctx: Context) =
        CameraManager(ctx)

    @Provides @Singleton
    fun provideImageSaver(
        @ApplicationContext ctx: Context,
        engine: ColorCorrectionEngine
    ) = ImageSaver(ctx, engine)
}
