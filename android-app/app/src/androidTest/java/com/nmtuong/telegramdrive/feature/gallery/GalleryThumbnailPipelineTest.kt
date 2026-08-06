package com.nmtuong.telegramdrive.feature.gallery

import android.graphics.Bitmap
import com.nmtuong.telegramdrive.domain.AccountSessionIdentity
import java.io.FileOutputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class GalleryThumbnailPipelineTest {
  private val identity = AccountSessionIdentity(1L, 1L)

  @Test
  fun invalidBase64DoesNotCrash() = runBlocking {
    val loader = GalleryThumbnailLoader()
    try {
      val bitmap = loader.load(
        GalleryThumbnailSource(identity, "invalid-base64", null, "not valid base64 !!!"),
        200,
        200,
      )
      assertNull(bitmap)
    } finally {
      loader.close()
    }
  }

  @Test
  fun missingFileDoesNotCrash() = runBlocking {
    val loader = GalleryThumbnailLoader()
    try {
      val bitmap = loader.load(
        GalleryThumbnailSource(identity, "missing-file", "/definitely/missing/gallery-thumb.jpg", null),
        200,
        200,
      )
      assertNull(bitmap)
    } finally {
      loader.close()
    }
  }

  @Test
  fun fileDecodeUsesTargetDownsampling() = runBlocking {
    val root = Files.createTempDirectory("gallery-thumbnail-").toFile()
    val path = root.resolve("large.png")
    val original = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888)
    FileOutputStream(path).use { output ->
      assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, output))
    }
    original.recycle()
    val loader = GalleryThumbnailLoader()
    try {
      val bitmap = loader.load(GalleryThumbnailSource(identity, "large", path.absolutePath, null), 200, 200)
      assertNotNull(bitmap)
      assertTrue(checkNotNull(bitmap).width <= 200)
      assertTrue(checkNotNull(bitmap).height <= 200)
    } finally {
      loader.close()
      path.delete()
      root.delete()
    }
  }
}
