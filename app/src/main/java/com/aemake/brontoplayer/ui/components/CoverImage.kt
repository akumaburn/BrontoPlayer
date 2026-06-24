package com.aemake.brontoplayer.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aemake.brontoplayer.R
import java.io.File

/**
 * Cover art for a book. Renders a branded gradient placeholder underneath, so a missing or
 * failed image degrades gracefully instead of showing a blank box.
 */
@Composable
fun CoverImage(
    coverPath: String?,
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        CoverPlaceholder(Modifier.fillMaxSize())
        if (coverPath != null) {
            AsyncImage(
                model = Uri.fromFile(File(coverPath)),
                contentDescription = stringResource(R.string.cover_art),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun CoverPlaceholder(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier.background(
            Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Headphones,
            contentDescription = null,
            tint = scheme.onPrimaryContainer.copy(alpha = 0.55f),
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp)
                .size(48.dp),
        )
    }
}
