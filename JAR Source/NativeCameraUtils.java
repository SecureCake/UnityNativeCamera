package com.yasirkula.unity;

import android.annotation.TargetApi;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Created by yasirkula on 30.04.2018.
 */

public class NativeCameraUtils
{
	private static String authority = null;
	private static String secondaryStoragePath = null;

	public static String GetAuthority( Context context )
	{
		if( authority == null )
		{
			// Find the authority of ContentProvider first
			// Credit: https://stackoverflow.com/a/2001769/2373034
			try
			{
				PackageInfo packageInfo = context.getPackageManager().getPackageInfo( context.getPackageName(), PackageManager.GET_PROVIDERS );
				ProviderInfo[] providers = packageInfo.providers;
				if( providers != null )
				{
					for( ProviderInfo provider : providers )
					{
						if( provider.name != null && provider.packageName != null && provider.authority != null &&
								provider.name.equals( NativeCameraContentProvider.class.getName() ) && provider.packageName.equals( context.getPackageName() )
								&& provider.authority.length() > 0 )
						{
							authority = provider.authority;
							break;
						}
					}
				}
			}
			catch( Exception e )
			{
				Log.e( "Unity", "Exception:", e );
			}
		}

		return authority;
	}

	// Credit: https://stackoverflow.com/a/9293885/2373034
	public static void CopyFile( File src, File dst ) throws IOException
	{
		if( !src.exists() )
			return;

		if( dst.exists() )
		{
			dst.delete();
			dst.createNewFile();
		}

		InputStream in = new FileInputStream( src );
		try
		{
			OutputStream out = new FileOutputStream( dst );
			try
			{
				byte[] buf = new byte[1024];
				int len;
				while( ( len = in.read( buf ) ) > 0 )
					out.write( buf, 0, len );
			}
			finally
			{
				out.close();
			}
		}
		finally
		{
			in.close();
		}
	}

	public static boolean IsPermissionDefinedInManifest( Context context, String permission )
	{
		try
		{
			String[] requestedPermissions = context.getPackageManager().getPackageInfo( context.getPackageName(), PackageManager.GET_PERMISSIONS ).requestedPermissions;
			if( requestedPermissions != null )
			{
				for( String requestedPermission : requestedPermissions )
				{
					if( permission.equals( requestedPermission ) )
						return true;
				}
			}
		}
		catch( PackageManager.NameNotFoundException e )
		{
		}

		return false;
	}

	// Credit: https://stackoverflow.com/a/36714242/2373034
	public static String getPathFromURI( Context context, Uri uri )
	{
		if( uri == null )
			return null;

		Log.d( "Unity", "Media uri: " + uri.toString() );

		String selection = null;
		String[] selectionArgs = null;

		try
		{
			if( Build.VERSION.SDK_INT >= 19 && DocumentsContract.isDocumentUri( context.getApplicationContext(), uri ) )
			{
				if( "com.android.externalstorage.documents".equals( uri.getAuthority() ) )
				{
					final String docId = DocumentsContract.getDocumentId( uri );
					final String[] split = docId.split( ":" );

					if( "primary".equalsIgnoreCase( split[0] ) )
						return Environment.getExternalStorageDirectory() + File.separator + split[1];
					else if( "raw".equalsIgnoreCase( split[0] ) ) // https://stackoverflow.com/a/51874578/2373034
						return split[1];

					return getSecondaryStoragePathFor( split[1] );
				}
				else if( "com.android.providers.downloads.documents".equals( uri.getAuthority() ) )
				{
					final String id = DocumentsContract.getDocumentId( uri );
					if( id.startsWith( "raw:" ) ) // https://stackoverflow.com/a/51874578/2373034
						return id.substring( 4 );

					uri = ContentUris.withAppendedId( Uri.parse( "content://downloads/public_downloads" ), Long.valueOf( id ) );
				}
				else if( "com.android.providers.media.documents".equals( uri.getAuthority() ) )
				{
					final String docId = DocumentsContract.getDocumentId( uri );
					final String[] split = docId.split( ":" );
					final String type = split[0];
					if( "image".equals( type ) )
						uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
					else if( "video".equals( type ) )
						uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
					else if( "audio".equals( type ) )
						uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
					else if( "raw".equals( type ) ) // https://stackoverflow.com/a/51874578/2373034
						return split[1];

					selection = "_id=?";
					selectionArgs = new String[] { split[1] };
				}
			}

			if( "content".equalsIgnoreCase( uri.getScheme() ) )
			{
				String[] projection = { MediaStore.Images.Media.DATA };
				Cursor cursor = null;

				try
				{
					cursor = context.getContentResolver().query( uri, projection, selection, selectionArgs, null );
					if( cursor != null )
					{
						int column_index = cursor.getColumnIndexOrThrow( MediaStore.Images.Media.DATA );
						if( cursor.moveToFirst() )
						{
							String columnValue = cursor.getString( column_index );
							if( columnValue != null && columnValue.length() > 0 )
								return columnValue;
						}
					}
				}
				catch( Exception e )
				{
				}
				finally
				{
					if( cursor != null )
						cursor.close();
				}
			}
			else if( "file".equalsIgnoreCase( uri.getScheme() ) )
				return uri.getPath();

			// File path couldn't be determined
			return null;
		}
		catch( Exception e )
		{
			Log.e( "Unity", "Exception:", e );
			return null;
		}
	}

	private static String getSecondaryStoragePathFor( String localPath )
	{
		if( secondaryStoragePath == null )
		{
			String primaryPath = Environment.getExternalStorageDirectory().getAbsolutePath();

			// Try paths saved at system environments
			// Credit: https://stackoverflow.com/a/32088396/2373034
			String strSDCardPath = System.getenv( "SECONDARY_STORAGE" );
			if( strSDCardPath == null || strSDCardPath.length() == 0 )
				strSDCardPath = System.getenv( "EXTERNAL_SDCARD_STORAGE" );

			if( strSDCardPath != null && strSDCardPath.length() > 0 )
			{
				if( !strSDCardPath.contains( ":" ) )
					strSDCardPath += ":";

				String[] externalPaths = strSDCardPath.split( ":" );
				for( int i = 0; i < externalPaths.length; i++ )
				{
					String path = externalPaths[i];
					if( path != null && path.length() > 0 )
					{
						File file = new File( path );
						if( file.exists() && file.isDirectory() && file.canRead() && !file.getAbsolutePath().equalsIgnoreCase( primaryPath ) )
						{
							String absolutePath = file.getAbsolutePath() + File.separator + localPath;
							if( new File( absolutePath ).exists() )
							{
								secondaryStoragePath = file.getAbsolutePath();
								return absolutePath;
							}
						}
					}
				}
			}

			// Try most common possible paths
			// Credit: https://gist.github.com/PauloLuan/4bcecc086095bce28e22
			String[] possibleRoots = new String[] { "/storage", "/mnt", "/storage/removable",
					"/removable", "/data", "/mnt/media_rw", "/mnt/sdcard0" };
			for( String root : possibleRoots )
			{
				try
				{
					File fileList[] = new File( root ).listFiles();
					for( File file : fileList )
					{
						if( file.exists() && file.isDirectory() && file.canRead() && !file.getAbsolutePath().equalsIgnoreCase( primaryPath ) )
						{
							String absolutePath = file.getAbsolutePath() + File.separator + localPath;
							if( new File( absolutePath ).exists() )
							{
								secondaryStoragePath = file.getAbsolutePath();
								return absolutePath;
							}
						}
					}
				}
				catch( Exception e )
				{
				}
			}

			secondaryStoragePath = "_NulL_";
		}
		else if( !secondaryStoragePath.equals( "_NulL_" ) )
			return secondaryStoragePath + File.separator + localPath;

		return null;
	}

	private static BitmapFactory.Options GetImageMetadata( final String path )
	{
		try
		{
			BitmapFactory.Options result = new BitmapFactory.Options();
			result.inJustDecodeBounds = true;
			BitmapFactory.decodeFile( path, result );

			return result;
		}
		catch( Exception e )
		{
			Log.e( "Unity", "Exception:", e );
			return null;
		}
	}

	// Credit: https://stackoverflow.com/a/30572852/2373034
	private static int GetImageOrientation( Context context, final String path )
	{
		try
		{
			ExifInterface exif = new ExifInterface( path );
			int orientationEXIF = exif.getAttributeInt( ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED );
			if( orientationEXIF != ExifInterface.ORIENTATION_UNDEFINED )
				return orientationEXIF;
		}
		catch( Exception e )
		{
		}

		Cursor cursor = null;
		try
		{
			cursor = context.getContentResolver().query( Uri.fromFile( new File( path ) ), new String[] { MediaStore.Images.Media.ORIENTATION }, null, null, null );
			if( cursor != null && cursor.moveToFirst() )
			{
				int orientation = cursor.getInt( cursor.getColumnIndex( MediaStore.Images.Media.ORIENTATION ) );
				if( orientation == 90 )
					return ExifInterface.ORIENTATION_ROTATE_90;
				if( orientation == 180 )
					return ExifInterface.ORIENTATION_ROTATE_180;
				if( orientation == 270 )
					return ExifInterface.ORIENTATION_ROTATE_270;

				return ExifInterface.ORIENTATION_NORMAL;
			}
		}
		catch( Exception e )
		{
		}
		finally
		{
			if( cursor != null )
				cursor.close();
		}

		return ExifInterface.ORIENTATION_UNDEFINED;
	}

	// Credit: https://gist.github.com/aviadmini/4be34097dfdb842ae066fae48501ed41
	private static Matrix GetImageOrientationCorrectionMatrix( final int orientation, final float scale )
	{
		Matrix matrix = new Matrix();

		switch( orientation )
		{
			case ExifInterface.ORIENTATION_ROTATE_270:
			{
				matrix.postRotate( 270 );
				matrix.postScale( scale, scale );

				break;
			}
			case ExifInterface.ORIENTATION_ROTATE_180:
			{
				matrix.postRotate( 180 );
				matrix.postScale( scale, scale );

				break;
			}
			case ExifInterface.ORIENTATION_ROTATE_90:
			{
				matrix.postRotate( 90 );
				matrix.postScale( scale, scale );

				break;
			}
			case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
			{
				matrix.postScale( -scale, scale );
				break;
			}
			case ExifInterface.ORIENTATION_FLIP_VERTICAL:
			{
				matrix.postScale( scale, -scale );
				break;
			}
			case ExifInterface.ORIENTATION_TRANSPOSE:
			{
				matrix.postRotate( 90 );
				matrix.postScale( -scale, scale );

				break;
			}
			case ExifInterface.ORIENTATION_TRANSVERSE:
			{
				matrix.postRotate( 270 );
				matrix.postScale( -scale, scale );

				break;
			}
			default:
			{
				matrix.postScale( scale, scale );
				break;
			}
		}

		return matrix;
	}

	public static String LoadImageAtPath( Context context, String path, final String temporaryFilePath, final int maxSize )
	{
		BitmapFactory.Options metadata = GetImageMetadata( path );
		if( metadata == null )
			return path;

		boolean shouldCreateNewBitmap = false;
		if( metadata.outWidth > maxSize || metadata.outHeight > maxSize )
			shouldCreateNewBitmap = true;

		if( metadata.outMimeType != null && !metadata.outMimeType.equals( "image/jpeg" ) && !metadata.outMimeType.equals( "image/png" ) )
			shouldCreateNewBitmap = true;

		int orientation = GetImageOrientation( context, path );
		if( orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED )
			shouldCreateNewBitmap = true;

		if( shouldCreateNewBitmap )
		{
			Bitmap bitmap = null;
			FileOutputStream out = null;

			try
			{
				// Credit: https://developer.android.com/topic/performance/graphics/load-bitmap.html
				int sampleSize = 1;
				int halfHeight = metadata.outHeight / 2;
				int halfWidth = metadata.outWidth / 2;
				while( ( halfHeight / sampleSize ) >= maxSize || ( halfWidth / sampleSize ) >= maxSize )
					sampleSize *= 2;

				BitmapFactory.Options options = new BitmapFactory.Options();
				options.inSampleSize = sampleSize;
				options.inJustDecodeBounds = false;
				bitmap = BitmapFactory.decodeFile( path, options );

				float scaleX = 1f, scaleY = 1f;
				if( bitmap.getWidth() > maxSize )
					scaleX = maxSize / (float) bitmap.getWidth();
				if( bitmap.getHeight() > maxSize )
					scaleY = maxSize / (float) bitmap.getHeight();

				// Create a new bitmap if it should be scaled down or if its orientation is wrong
				float scale = scaleX < scaleY ? scaleX : scaleY;
				if( scale < 1f || ( orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED ) )
				{
					Matrix transformationMatrix = GetImageOrientationCorrectionMatrix( orientation, scale );
					Bitmap transformedBitmap = Bitmap.createBitmap( bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), transformationMatrix, true );
					if( transformedBitmap != bitmap )
					{
						bitmap.recycle();
						bitmap = transformedBitmap;
					}
				}

				out = new FileOutputStream( temporaryFilePath );
				if( metadata.outMimeType == null || !metadata.outMimeType.equals( "image/jpeg" ) )
					bitmap.compress( Bitmap.CompressFormat.PNG, 100, out );
				else
					bitmap.compress( Bitmap.CompressFormat.JPEG, 100, out );

				path = temporaryFilePath;
			}
			catch( Exception e )
			{
				Log.e( "Unity", "Exception:", e );

				try
				{
					File temporaryFile = new File( temporaryFilePath );
					if( temporaryFile.exists() )
						temporaryFile.delete();
				}
				catch( Exception e2 )
				{
				}
			}
			finally
			{
				if( bitmap != null )
					bitmap.recycle();

				try
				{
					if( out != null )
						out.close();
				}
				catch( Exception e )
				{
				}
			}
		}

		return path;
	}

	public static String GetImageProperties( Context context, final String path )
	{
		BitmapFactory.Options metadata = GetImageMetadata( path );
		if( metadata == null )
			return "";

		int width = metadata.outWidth;
		int height = metadata.outHeight;

		String mimeType = metadata.outMimeType;
		if( mimeType == null )
			mimeType = "";

		int orientationUnity;
		int orientation = GetImageOrientation( context, path );
		if( orientation == ExifInterface.ORIENTATION_UNDEFINED )
			orientationUnity = -1;
		else if( orientation == ExifInterface.ORIENTATION_NORMAL )
			orientationUnity = 0;
		else if( orientation == ExifInterface.ORIENTATION_ROTATE_90 )
			orientationUnity = 1;
		else if( orientation == ExifInterface.ORIENTATION_ROTATE_180 )
			orientationUnity = 2;
		else if( orientation == ExifInterface.ORIENTATION_ROTATE_270 )
			orientationUnity = 3;
		else if( orientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL )
			orientationUnity = 4;
		else if( orientation == ExifInterface.ORIENTATION_TRANSPOSE )
			orientationUnity = 5;
		else if( orientation == ExifInterface.ORIENTATION_FLIP_VERTICAL )
			orientationUnity = 6;
		else if( orientation == ExifInterface.ORIENTATION_TRANSVERSE )
			orientationUnity = 7;
		else
			orientationUnity = -1;

		if( orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
				orientation == ExifInterface.ORIENTATION_TRANSPOSE || orientation == ExifInterface.ORIENTATION_TRANSVERSE )
		{
			int temp = width;
			width = height;
			height = temp;
		}

		return width + ">" + height + ">" + mimeType + ">" + orientationUnity;
	}

	@TargetApi( Build.VERSION_CODES.JELLY_BEAN_MR1 )
	public static String GetVideoProperties( Context context, final String path )
	{
		MediaMetadataRetriever metadataRetriever = new MediaMetadataRetriever();
		metadataRetriever.setDataSource( path );

		String width = metadataRetriever.extractMetadata( MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH );
		String height = metadataRetriever.extractMetadata( MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT );
		String duration = metadataRetriever.extractMetadata( MediaMetadataRetriever.METADATA_KEY_DURATION );
		String rotation = "0";
		if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 )
			rotation = metadataRetriever.extractMetadata( MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION );

		if( width == null )
			width = "0";
		if( height == null )
			height = "0";
		if( duration == null )
			duration = "0";
		if( rotation == null )
			rotation = "0";

		return width + ">" + height + ">" + duration + ">" + rotation;
	}
}