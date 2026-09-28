package com.example.objectremover.objectremoverapp

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.objectremover.HomeAccentPurple
import com.example.objectremover.IconGlyph
import com.example.objectremover.InteractiveSegmenterHelper
import com.example.objectremover.drawImageGlyph
import com.example.objectremover.suppressReturnTransition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class MediaImage(
    val id: Long,
    val uri: Uri
)
private data class MediaAlbum(
    val bucketId: Long,
    val name: String,
    val thumbnailUri: Uri,
    val count: Int
)
private object MediaStoreHelper {
    fun queryImages(context: Context, bucketId: Long?): List<MediaImage> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = if (bucketId != null) "${MediaStore.Images.Media.BUCKET_ID} = ?" else null
        val selectionArgs = if (bucketId != null) arrayOf(bucketId.toString()) else null
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val images = mutableListOf<MediaImage>()

        try { context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(collection, id)
                images.add(MediaImage(id, uri))
            }
        }
        } catch (e: Exception) { e.printStackTrace() }
        return images
    }

    fun queryAlbums(context: Context): List<MediaAlbum> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        data class Accum(
            val bucketId: Long,
            var name: String,
            var thumbnailUri: Uri,
            var count: Int
        )
        val buckets = LinkedHashMap<Long, Accum>()
        try { context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getLong(bucketIdColumn)
                val existing = buckets[bucketId]
                if (existing == null) {
                    val id = cursor.getLong(idColumn)
                    val thumbnailUri = ContentUris.withAppendedId(collection, id)
                    val name = cursor.getString(bucketNameColumn) ?: "Unknown"
                    buckets[bucketId] = Accum(
                        bucketId = bucketId,
                        name = name,
                        thumbnailUri = thumbnailUri,
                        count = 1
                    )
                } else {
                    existing.count += 1
                }
            }
        }
        } catch (e: Exception) { e.printStackTrace() }
        return buckets.values.map { acc -> MediaAlbum(
            bucketId = acc.bucketId,
            name = acc.name,
            thumbnailUri = acc.thumbnailUri,
            count = acc.count
        )
        }
    }
}

@Composable
private fun rememberImageThumbnail(context: Context, uri: Uri): State<ImageBitmap?> {
    return produceState<ImageBitmap?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) {
            try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver
                    .loadThumbnail(uri, android.util.Size(300, 300), null)
                    .asImageBitmap()

            } else {

                context.contentResolver.openInputStream(uri)?.use { input ->
                    val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory
                        .decodeStream(input, null, options)
                        ?.asImageBitmap()
                }
            }
            } catch (e: Exception) {
                null
            }
        }
    }
}

@Composable
fun PhotoPickerScreen(onClose: () -> Unit, onImageSelected: (Uri) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val interactiveSegmenter = remember { InteractiveSegmenterHelper(context) }
    DisposableEffect(Unit) { onDispose { interactiveSegmenter.close() } }
    val photoPermissions = remember { when {Build.VERSION.SDK_INT >= 34 ->
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else ->
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    }

    fun hasAnyPhotoPermission(): Boolean = photoPermissions.any {
        ContextCompat.checkSelfPermission(context, it) ==
                PackageManager.PERMISSION_GRANTED
    }

    var hasPermission by remember { mutableStateOf(hasAnyPhotoPermission()) }
    var permissionRequestFinished by remember { mutableStateOf(hasPermission) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        hasPermission = hasAnyPhotoPermission()
        permissionRequestFinished = true
    }

    LaunchedEffect(Unit) { if (!hasPermission) {
        permissionLauncher.launch(photoPermissions) } }

    var selectedAlbum by remember { mutableStateOf<MediaAlbum?>(null) }
    var albums by remember { mutableStateOf<List<MediaAlbum>>(emptyList()) }
    var isLoadingAlbums by remember { mutableStateOf(false) }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            isLoadingAlbums = true
            albums = withContext(Dispatchers.IO) {
                MediaStoreHelper.queryAlbums(context)
            }
            isLoadingAlbums = false
        }
    }

    var albumPhotos by remember { mutableStateOf<List<MediaImage>>(emptyList()) }
    var isLoadingAlbumPhotos by remember { mutableStateOf(false) }
    LaunchedEffect(selectedAlbum) {
        val album = selectedAlbum
        if (album != null) {
            isLoadingAlbumPhotos = true
            albumPhotos = withContext(Dispatchers.IO) {
                MediaStoreHelper.queryImages(context, bucketId = album.bucketId)
            }
            isLoadingAlbumPhotos = false
        }
    }

    fun goBack() {
        if (selectedAlbum != null) {
            selectedAlbum = null
        } else {
            onClose()
        }
    }

    BackHandler(enabled = true) { goBack() }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            onImageSelected(uri)
        } else if (uri != null) {
            try { context.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        pendingCameraUri = null
    }

    fun insertAndLaunchCamera() {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        )

        if (uri != null) {
            pendingCameraUri = uri
            suppressReturnTransition(context)
            cameraLauncher.launch(uri)
        } else {
            Toast.makeText(context, "Couldn't open camera", Toast.LENGTH_SHORT).show()
        }
    }

    var hasCameraPermission by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted
        if (granted) { insertAndLaunchCamera() }
    }

    fun launchCamera() {
        if (hasCameraPermission) { insertAndLaunchCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.White)
    ) {

        PhotoPickerTopBar(title = selectedAlbum?.name ?: "All Photos", onClose = { goBack() })
        Box(modifier = Modifier.fillMaxSize()) { val album = selectedAlbum
            when {album == null -> {
                when {!hasPermission && permissionRequestFinished -> {
                    PermissionDeniedState(onOpenSettings = {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                        suppressReturnTransition(context)
                        context.startActivity(intent)
                    }
                    )
                }

                    isLoadingAlbums && albums.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator(color = HomeAccentPurple)
                        }
                    }

                    else -> {

                        AlbumListScreen(albums = albums,
                            onCameraClick = { launchCamera() },
                            onAlbumClick = { selectedAlbum = it }
                        )
                    }
                }
            }

                else -> {

                    if (isLoadingAlbumPhotos && albumPhotos.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator(color = HomeAccentPurple)
                        }

                    } else {

                        AlbumPhotosGrid(images = albumPhotos,
                            onImageClick = { onImageSelected(it.uri) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoPickerTopBar(
    title: String,
    onClose: () -> Unit
) {

    Box(modifier = Modifier.fillMaxWidth()
        .background(Color.White)
        .padding(horizontal = 4.dp, vertical = 12.dp)
    ) {

        IconButton(onClick = onClose,
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            IconGlyph(modifier = Modifier.size(20.dp),
                tint = Color.Black
            ) { drawCloseGlyph(it) }
        }

        Text(
            text = title,
            color = Color.Black,
            fontSize = 16.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun PermissionDeniedState(onOpenSettings: () -> Unit) {
    Box(modifier = Modifier
        .fillMaxSize()
        .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconGlyph(modifier = Modifier.size(40.dp),
                tint = Color.Gray
            ) { drawImageGlyph(it) }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Photo access is turned off",
                fontSize = 16.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                color = Color.Black,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Allow photo access in Settings to see your albums here.",
                fontSize = 13.sp,
                color = Color.DarkGray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(
                    containerColor = HomeAccentPurple,
                    contentColor = Color.White
                )
            ) {
                Text(text = "Open Settings")
            }
        }
    }
}
@Composable
private fun AlbumListScreen(
    albums: List<MediaAlbum>,
    onCameraClick: () -> Unit,
    onAlbumClick: (MediaAlbum) -> Unit
) {

    LazyColumn(modifier = Modifier.fillMaxSize().background(Color.White)) {
        item { CameraRow(onClick = onCameraClick)
            AlbumListDivider()
        }

        itemsIndexed(albums, key = { _, album -> album.bucketId }) { index, album ->
            AlbumRow(album = album, onClick = { onAlbumClick(album) })
            if (index != albums.lastIndex) {
                AlbumListDivider()
            }
        }
    }
}

@Composable
private fun AlbumListDivider() {

    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.75.dp,
        color = Color(0xFFE6E6E6)
    )
}

@Composable
private fun CameraRow(onClick: () -> Unit) {

    Row(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Box(modifier = Modifier
            .size(112.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFF2F2F2)),
            contentAlignment = Alignment.Center
        ) {

            IconGlyph(
                modifier = Modifier.size(56.dp),
                tint = Color.DarkGray
            ) { drawCameraGlyph(it) }
        }

        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = "Camera",
            fontSize = 20.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = Color.Black
        )
    }
}

@Composable
private fun AlbumRow(album: MediaAlbum, onClick: () -> Unit) {

    val context = androidx.compose.ui.platform.LocalContext.current
    val thumbnail by rememberImageThumbnail(context, album.thumbnailUri)
    Row(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Box(modifier = Modifier
            .size(112.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFEDEDED))
        ) {

            val bmp = thumbnail
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(
                text = album.name,
                fontSize = 20.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                color = Color.Black
            )

            Text(
                text = album.count.toString(),
                fontSize = 16.sp,
                color = Color.DarkGray
            )
        }
    }
}

@Composable
private fun AlbumPhotosGrid(
    images: List<MediaImage>,
    onImageClick: (MediaImage) -> Unit
) {

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {

        items(images, key = { it.id }) { image ->
            PhotoThumbnail(image = image, onClick = { onImageClick(image) })
        }
    }
}


@Composable
private fun PhotoThumbnail(image: MediaImage, onClick: () -> Unit) {

    val context = androidx.compose.ui.platform.LocalContext.current
    val thumbnail by rememberImageThumbnail(context, image.uri)
    Box(modifier = Modifier
        .aspectRatio(1f)
        .padding(1.dp)
        .background(Color(0xFFEDEDED))
        .clickable(onClick = onClick)
    ) {

        val bmp = thumbnail
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun DrawScope.drawCloseGlyph(tint: Color) {

    val w = size.width
    val h = size.height
    val sw = w * 0.12f

    drawLine(tint, Offset(w * 0.2f, h * 0.2f), Offset(w * 0.8f, h * 0.8f), sw, cap = StrokeCap.Round)
    drawLine(tint, Offset(w * 0.8f, h * 0.2f), Offset(w * 0.2f, h * 0.8f), sw, cap = StrokeCap.Round)
}

private fun DrawScope.drawCameraGlyph(tint: Color) {

    val w = size.width
    val h = size.height
    val sw = w * 0.07f

    drawRoundRect(
        color = tint,
        topLeft = Offset(w * 0.1f, h * 0.28f),
        size = Size(w * 0.8f, h * 0.55f),
        cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
        style = Stroke(width = sw)
    )

    drawRoundRect(
        color = tint,
        topLeft = Offset(w * 0.38f, h * 0.15f),
        size = Size(w * 0.24f, h * 0.14f),
        cornerRadius = CornerRadius(w * 0.03f, w * 0.03f),
        style = Stroke(width = sw * 0.8f)
    )

    drawCircle(
        tint,
        radius = w * 0.16f,
        center = Offset(w * 0.5f, h * 0.56f),
        style = Stroke(width = sw)
    )
}