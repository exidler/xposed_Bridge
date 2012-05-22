package android.content.res;

import static de.robv.android.xposed.XposedHelpers.getObjectField;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.WeakHashMap;

import org.xmlpull.v1.XmlPullParser;

import android.graphics.Movie;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import de.robv.android.xposed.Callback;
import de.robv.android.xposed.MethodSignatureGuide;
import de.robv.android.xposed.XposedBridge;

/**
 * Resources class that allows replacements for selected resources
 */
public class XResources extends Resources {
	private static final HashMap<Integer, HashMap<String, Object>> replacements = new HashMap<Integer, HashMap<String, Object>>();
	private static final HashMap<Integer, HashMap<String, ResourceNames>> resourceNames
		= new HashMap<Integer, HashMap<String, ResourceNames>>();
	
	private static final HashMap<Integer, HashMap<String, SortedSet<Callback>>> layoutCallbacks
		= new HashMap<Integer, HashMap<String, SortedSet<Callback>>>();
	private static final Class<?>[] HOOK_LAYOUT_CALLBACK_PARAMS
		= new Class[] { View.class, ResourceNames.class, String.class, XResources.class };
	private static final WeakHashMap<XmlResourceParser, XMLInstanceDetails> xmlInstanceDetails
		= new WeakHashMap<XmlResourceParser, XMLInstanceDetails>();
	
	private static final HashMap<String, String> resDirToPackage = new HashMap<String, String>();
	private static final HashMap<String, Long> resDirLastModified = new HashMap<String, Long>();
	private boolean inited = false;

	private final String resDir;
	
	public XResources(Resources parent, String resDir) {
		super(parent.getAssets(), parent.getDisplayMetrics(), parent.getConfiguration(), parent.getCompatibilityInfo());
		this.resDir = resDir;
	}
	
	public boolean checkFirstLoad() {
		synchronized (replacements) {
			if (resDir == null)
				return false;
			
			Long lastModification = new File(resDir).lastModified();
			Long oldModified = resDirLastModified.get(resDir);
			if (lastModification.equals(oldModified))
				return false;
			
			resDirLastModified.put(resDir, lastModification);
			
			if (oldModified == null)
				return true;
			
			// file was changed meanwhile => remove old replacements 
			for (HashMap<String, Object> inner : replacements.values()) {
				inner.remove(resDir);
			}
			return true;
		}
	}

	public static void setPackageNameForResDir(String resDir, String packageName) {
		resDirToPackage.put(resDir, packageName);
	}
	
	public String getResDir() {
		return resDir;
	}
	
	public String getPackageName() {
		if (resDir == null)
			return "android";
		return resDirToPackage.get(resDir);
	}
	
	public boolean isInited() {
		return inited;
	}
	
	public void setInited(boolean inited) {
		this.inited = inited;
	}
	
	public static void init() throws Exception {
		XposedBridge.hookMethod(Resources.class.getDeclaredMethod("getCachedStyledAttributes", int.class),
				XResources.class, "handleGetCachedStyledAttributes", Callback.PRIORITY_DEFAULT);
		
		Method methodInflate = LayoutInflater.class.getDeclaredMethod("inflate", XmlPullParser.class, ViewGroup.class, boolean.class);
		XposedBridge.hookMethod(methodInflate, XResources.class, "handleInflate", Callback.PRIORITY_DEFAULT);
	}
	
	public static class ResourceNames {
		public final int id;
		public final String pkg;
		public final String name;
		public final String type;
		public final String fullName;
		
		private ResourceNames(int id, String pkg, String name, String type) {
			this.id = id;
			this.pkg = pkg;
			this.name = name;
			this.type = type;
			this.fullName = pkg + ":" + type + "/" + name;
		}
		
		/**
		 * Returns <code>true</code> if all non-null parameters match the values of this object.
		 */
		public boolean equals(String pkg, String name, String type, int id) {
			return (pkg  == null || pkg.equals(this.pkg))
				&& (name == null || name.equals(this.name))
				&& (type == null || type.equals(this.type))
				&& (id == 0 || id == this.id);
		}
	}
	
	private ResourceNames getResourceNames(int id) {
		return new ResourceNames(
				id,
				getResourcePackageName(id),
				getResourceTypeName(id),
				getResourceEntryName(id));
	}
	
	private static ResourceNames getSystemResourceNames(int id) {
		Resources sysRes = getSystem();
		return new ResourceNames(
				id,
				sysRes.getResourcePackageName(id),
				sysRes.getResourceTypeName(id),
				sysRes.getResourceEntryName(id));
	}
	
	private static void putResourceNames(String resDir, ResourceNames resNames) {
		int id = resNames.id;
		synchronized (resourceNames) {
			HashMap<String, ResourceNames> inner = resourceNames.get(id);
			if (inner == null) {
				inner = new HashMap<String, ResourceNames>();
				resourceNames.put(id, inner);
			}
			inner.put(resDir, resNames);
		}
	}

	// =======================================================
	//   DEFINING REPLACEMENTS
	// =======================================================
	
	public void setReplacement(int id, Object replacement) {
		setReplacement(id, replacement, resDir);
	}
	
	public void setReplacement(String fullName, Object replacement) {
		int id = getIdentifier(fullName, null, null);
		if (id == 0)
			throw new NotFoundException(fullName);
		setReplacement(id, replacement, resDir);
	}
	
	public void setReplacement(String pkg, String type, String name, Object replacement) {
		int id = getIdentifier(name, type, pkg);
		if (id == 0)
			throw new NotFoundException(pkg + ":" + type + "/" + name);
		setReplacement(id, replacement, resDir);
	}
	
	public static void setSystemWideReplacement(int id, Object replacement) {
		setReplacement(id, replacement, null);
	}
	
	public static void setSystemWideReplacement(String fullName, Object replacement) {
		int id = getSystem().getIdentifier(fullName, null, null);
		if (id == 0)
			throw new NotFoundException(fullName);
		setReplacement(id, replacement, null);
	}
	
	public static void setSystemWideReplacement(String pkg, String type, String name, Object replacement) {
		int id = getSystem().getIdentifier(name, type, pkg);
		if (id == 0)
			throw new NotFoundException(pkg + ":" + type + "/" + name);
		setReplacement(id, replacement, null);
	}
	
	private static void setReplacement(int id, Object replacement, String resDir) {
		if (id == 0)
			throw new IllegalArgumentException("id 0 is not an allowed resource identifier");
		else if (resDir == null && id >= 0x7f000000)
			throw new IllegalArgumentException("ids >= 0x7f000000 are app specific and cannot be set for the framework");
		
		synchronized (replacements) {
			HashMap<String, Object> inner = replacements.get(id);
			if (inner == null) {
				inner = new HashMap<String, Object>();
				replacements.put(id, inner);
			}
			inner.put(resDir, replacement);
		}
	}
	
	// =======================================================
	//   RETURNING REPLACEMENTS
	// =======================================================
	
	private Object getReplacement(int id) {
		if (id <= 0)
			return null;
		
		HashMap<String, Object> inner = replacements.get(id);
		if (inner == null)
			return null;
		Object result = inner.get(resDir);
		if (result != null || resDir == null)
			return result;
		return inner.get(null);
	}
	
	@Override
	public boolean getBoolean(int id) throws NotFoundException {
		Object replacement = getReplacement(id);
		if (replacement instanceof Boolean) {
			return (Boolean) replacement;
		} else if (replacement instanceof XResForwarder) {
			Resources repRes = ((XResForwarder) replacement).getResources();
			int repId = ((XResForwarder) replacement).getId();
			return repRes.getBoolean(repId);
		}
		return super.getBoolean(id);
	}
	
	@Override
	public int getColor(int id) throws NotFoundException {
		Object replacement = getReplacement(id);
		if (replacement instanceof Integer) {
			return (Integer) replacement;
		} else if (replacement instanceof XResForwarder) {
			Resources repRes = ((XResForwarder) replacement).getResources();
			int repId = ((XResForwarder) replacement).getId();
			return repRes.getColor(repId);
		}
		return super.getColor(id);
	}
	
	@Override
	public float getDimension(int id) throws NotFoundException {
		Object replacement = getReplacement(id);
		if (replacement instanceof XResForwarder) {
			Resources repRes = ((XResForwarder) replacement).getResources();
			int repId = ((XResForwarder) replacement).getId();
			return repRes.getDimension(repId);
		}
		return super.getDimension(id);
	}
	
	@Override
	public int getDimensionPixelOffset(int id) throws NotFoundException {
		Object replacement = getReplacement(id);
		if (replacement instanceof XResForwarder) {
			Resources repRes = ((XResForwarder) replacement).getResources();
			int repId = ((XResForwarder) replacement).getId();
			return repRes.getDimensionPixelOffset(repId);
		}
		return super.getDimensionPixelOffset(id);
	}
	
	@Override
	public int getDimensionPixelSize(int id) throws NotFoundException {
		Object replacement = getReplacement(id);
		if (replacement instanceof XResForwarder) {
			Resources repRes = ((XResForwarder) replacement).getResources();
			int repId = ((XResForwarder) replacement).getId();
			return repRes.getDimensionPixelSize(repId);
		}
		return super.getDimensionPixelSize(id);
	}
	
	@Override
	public Drawable getDrawable(int id) throws NotFoundException {
		Object replacement = getReplacement(id);
		if (replacement instanceof Drawable) {
			return (Drawable) replacement;
		} else if (replacement instanceof XResForwarder) {
			Resources repRes = ((XResForwarder) replacement).getResources();
			int repId = ((XResForwarder) replacement).getId();
			return repRes.getDrawable(repId);
		}
		return super.getDrawable(id);
	}
	
	@Override
	public Drawable getDrawableForDensity(int id, int density) throws NotFoundException {
		Object replacement = getReplacement(id);
		if (replacement instanceof Drawable) {
			return (Drawable) replacement;
		} else if (replacement instanceof XResForwarder) {
			Resources repRes = ((XResForwarder) replacement).getResources();
			int repId = ((XResForwarder) replacement).getId();
			return repRes.getDrawableForDensity(repId, density);
		}
		return super.getDrawableForDensity(id, density);
	}
	
	@Override
	public float getFraction(int id, int base, int pbase) {
		Object replacement = getReplacement(id);
		if (replacement instanceof XResForwarder) {
			Resources repRes = ((XResForwarder) replacement).getResources();
			int repId = ((XResForwarder) replacement).getId();
			return repRes.getFraction(repId, base, pbase);
		}
		return super.getFraction(id, base, pbase);
	}
	
	@Override
	public int getInteger(int id) throws NotFoundException {
		Object replacement = getReplacement(id);
		if (replacement instanceof Integer) {
			return (Integer) replacement;
		} else if (replacement instanceof XResForwarder) {
			Resources repRes = ((XResForwarder) replacement).getResources();
			int repId = ((XResForwarder) replacement).getId();
			return repRes.getInteger(repId);
		}
		return super.getInteger(id);
	}
	
	@Override
	public int[] getIntArray(int id) throws NotFoundException {
		Object replacement = getReplacement(id);
		if (replacement instanceof int[]) {
			return (int[]) replacement;
		} else if (replacement instanceof XResForwarder) {
			Resources repRes = ((XResForwarder) replacement).getResources();
			int repId = ((XResForwarder) replacement).getId();
			return repRes.getIntArray(repId);
		}
		return super.getIntArray(id);
	}
	
	@Override
	public Movie getMovie(int id) throws NotFoundException {
		Object replacement = getReplacement(id);
		if (replacement instanceof XResForwarder) {
			Resources repRes = ((XResForwarder) replacement).getResources();
			int repId = ((XResForwarder) replacement).getId();
			return repRes.getMovie(repId);
		}
		return super.getMovie(id);
	}
	
	@Override
	public CharSequence getQuantityText(int id, int quantity) throws NotFoundException {
		Object replacement = getReplacement(id);
		if (replacement instanceof XResForwarder) {
			Resources repRes = ((XResForwarder) replacement).getResources();
			int repId = ((XResForwarder) replacement).getId();
			return repRes.getQuantityText(repId, quantity);
		}
		return super.getQuantityText(id, quantity);
	}
	// these are handled by getQuantityText:
	// public String getQuantityString(int id, int quantity);
	// public String getQuantityString(int id, int quantity, Object... formatArgs);
	
	@Override
	public String[] getStringArray(int id) throws NotFoundException {
		Object replacement = getReplacement(id);
		if (replacement instanceof String[]) {
			return (String[]) replacement;
		} else if (replacement instanceof XResForwarder) {
			Resources repRes = ((XResForwarder) replacement).getResources();
			int repId = ((XResForwarder) replacement).getId();
			return repRes.getStringArray(repId);
		}
		return super.getStringArray(id);
	}
	
	@Override
	public CharSequence getText(int id) throws NotFoundException {
		Object replacement = getReplacement(id);
		if (replacement instanceof CharSequence) {
			return (CharSequence) replacement;
		} else if (replacement instanceof XResForwarder) {
			Resources repRes = ((XResForwarder) replacement).getResources();
			int repId = ((XResForwarder) replacement).getId();
			return repRes.getText(repId);
		}
		return super.getText(id);
	}
	// these are handled by getText:
	// public String getString(int id);
	// public String getString(int id, Object... formatArgs);
	
	@Override
	public CharSequence getText(int id, CharSequence def) {
		Object replacement = getReplacement(id);
		if (replacement instanceof CharSequence) {
			return (CharSequence) replacement;
		} else if (replacement instanceof XResForwarder) {
			Resources repRes = ((XResForwarder) replacement).getResources();
			int repId = ((XResForwarder) replacement).getId();
			return repRes.getText(repId, def);
		}
		return super.getText(id, def);
	}
	
	@Override
	public CharSequence[] getTextArray(int id) throws NotFoundException {
		Object replacement = getReplacement(id);
		if (replacement instanceof CharSequence[]) {
			return (CharSequence[]) replacement;
		} else if (replacement instanceof XResForwarder) {
			Resources repRes = ((XResForwarder) replacement).getResources();
			int repId = ((XResForwarder) replacement).getId();
			return repRes.getTextArray(repId);
		}
		return super.getTextArray(id);
	}
	
	@Override
	XmlResourceParser loadXmlResourceParser(int id, String type) throws NotFoundException {
		XmlResourceParser result;
		Object replacement = getReplacement(id);
		if (replacement instanceof XResForwarder) {
			Resources repRes = ((XResForwarder) replacement).getResources();
			int repId = ((XResForwarder) replacement).getId();
			
			boolean loadFromCache = false;
			int[] mCachedXmlBlockIds = (int[]) getObjectField(repRes, "mCachedXmlBlockIds");

			synchronized (mCachedXmlBlockIds) {
				// First see if this block is in our cache.
				final int num = mCachedXmlBlockIds.length;
				for (int i=0; i<num; i++) {
					if (mCachedXmlBlockIds[i] == repId) {
						loadFromCache = true;
					}
				}
			}

			result = repRes.loadXmlResourceParser(repId, type);

			if (!loadFromCache)
				rewriteXmlReferencesNative(((XmlBlock.Parser) result).mParseState, this, repRes);
		} else {
			result = super.loadXmlResourceParser(id, type);
		}
		
		if (type.equals("layout")) {
			HashMap<String, SortedSet<Callback>> inner = layoutCallbacks.get(id);
			if (inner != null) {
				SortedSet<Callback> callbacks = inner.get(resDir);
				if (callbacks == null && resDir != null)
					callbacks = inner.get(null);
				
				if (callbacks != null) {
					String variant = "layout";
					TypedValue value = mTmpValue;
					getValue(id, value, true);
					if (value.type == TypedValue.TYPE_STRING) {
						String[] components = value.string.toString().split("/", 3);
						if (components.length == 3)
							variant = components[1];
						else
							XposedBridge.log("Unexpected resource path \"" + value.string.toString()
									+ "\" for resource id 0x" + Integer.toHexString(id));
					} else {
						XposedBridge.log(new NotFoundException("Could not find file name for resource id 0x") + Integer.toHexString(id));
					}
					
					xmlInstanceDetails.put(result, new XMLInstanceDetails(resourceNames.get(id).get(resDir), variant, callbacks));
				}
			}
		}
		
		return result;
	}
	// these are handled via loadXmlResourceParser: 
	// public XmlResourceParser getAnimation(int id);
	// public ColorStateList getColorStateList(int id);
	// public XmlResourceParser getLayout(int id);
	// public XmlResourceParser getXml(int id);
	

	private static native void rewriteXmlReferencesNative(int parserPtr, XResources origRes, Resources repRes);
	
	/**
	 * Used to replace reference IDs in XMLs.
	 * 
	 * When resource requests are forwarded to modules, the may include references to resources with the same
	 * name as in the original resources, but the IDs generated by aapt will be different. rewriteXmlReferencesNative
	 * walks through all references and calls this function to find out the original ID, which it then writes to
	 * the compiled XML file in the memory.
	 */
	private static int translateResId(int id, XResources origRes, Resources repRes) {
		try {
			String entryName = repRes.getResourceEntryName(id);
			String entryType = repRes.getResourceTypeName(id);
			String origPackage = origRes.getPackageName();
			int origResId = 0;
			try {
				// look for a resource with the same name and type in the original package
				origResId = origRes.getIdentifier(entryName, entryType, origPackage);
			} catch (NotFoundException ignored) {}
			
			boolean repResDefined = false;
			try {
				final TypedValue tmpValue = new TypedValue();
				repRes.getValue(id, tmpValue, false);
				// if a resource has not been defined (i.e. only a resource ID has been created), it will equal "false"
				// this means a boolean "false" value is not detected of it is directly referenced in an XML file
				repResDefined = !(tmpValue.type == TypedValue.TYPE_INT_BOOLEAN && tmpValue.data == 0);
			} catch (NotFoundException ignored) {}
			
			if (!repResDefined && origResId == 0 && !entryType.equals("id")) {
				XposedBridge.log(entryType + "/" + entryName + " is neither defined in module nor in original resources");
				return 0;
			}
			
			// exists only in module, so create a fake resource id
			if (origResId == 0)
				origResId = getFakeResId(repRes, id);
			
			// IDs will never be loaded, no need to set a replacement
			if (repResDefined && !entryType.equals("id"))
				origRes.setReplacement(origResId, new XResForwarder(repRes, id));
			
			return origResId;
		} catch (Exception e) {
			XposedBridge.log(e);
			return id;
		}
	}
	
	public static int getFakeResId(String resName) {
		return 0x7e000000 + resName.hashCode() & 0x00ffffff;
	}
	
	public static int getFakeResId(Resources res, int id) {
		return getFakeResId(res.getResourceEntryName(id));
	}
	
	/**
	 * Similar to {@link #translateResId}, but used to determine the original ID of attribute names
	 */
	private static int translateAttrId(String attrName, XResources origRes) {
		String origPackage = origRes.getPackageName();
		int origAttrId = 0;
		try {
			origAttrId = origRes.getIdentifier(attrName, "attr", origPackage);
		} catch (NotFoundException e) {
			XposedBridge.log("Attribute " + attrName + " not found in original resources");
		}
		return origAttrId;
	}
	
	/**
	 * Instead of creating {@link TypedArray}s, creates {@link XTypedArray}s, which have support for
	 * replacing values.
	 */
	private static Object handleGetCachedStyledAttributes(Iterator<Callback> iterator, Method method, Object thisObject, Object[] args) throws Throwable {
		Object result = XposedBridge.callNext(iterator, method, thisObject, args);
		if (!(result instanceof XTypedArray) && thisObject instanceof XResources) {
			TypedArray orig = (TypedArray) result;
			XResources xres = (XResources) thisObject;
			result = xres.newXTypedArray(orig.mData, orig.mIndices, orig.mLength);
		}
		return result;
	}
	
	
	
	// =======================================================
	//   XTypedArray class
	// =======================================================
	
	private XTypedArray newXTypedArray(int[] data, int[] indices, int len) {
		return new XTypedArray(this, data, indices, len);
	}
	
	/**
	 * {@link TypedArray} replacement that replaces values on-the-fly.
	 * Mainly used when inflating layouts.
	 */
	public class XTypedArray extends TypedArray {
		XTypedArray(Resources resources, int[] data, int[] indices, int len) {
			super(resources, data, indices, len);
		}
		
		@Override
		public boolean getBoolean(int index, boolean defValue) {
			Object replacement = getReplacement(getResourceId(index, 0));
			if (replacement instanceof Boolean) {
				return (Boolean) replacement;
			} else if (replacement instanceof XResForwarder) {
				Resources repRes = ((XResForwarder) replacement).getResources();
				int repId = ((XResForwarder) replacement).getId();
				return repRes.getBoolean(repId);
			}
			return super.getBoolean(index, defValue);
		}
		
		@Override
		public int getColor(int index, int defValue) {
			Object replacement = getReplacement(getResourceId(index, 0));
			if (replacement instanceof Integer) {
				return (Integer) replacement;
			} else if (replacement instanceof XResForwarder) {
				Resources repRes = ((XResForwarder) replacement).getResources();
				int repId = ((XResForwarder) replacement).getId();
				return repRes.getColor(repId);
			}
			return super.getColor(index, defValue);
		}
		
		@Override
		public float getDimension(int index, float defValue) {
			Object replacement = getReplacement(getResourceId(index, 0));
			if (replacement instanceof XResForwarder) {
				Resources repRes = ((XResForwarder) replacement).getResources();
				int repId = ((XResForwarder) replacement).getId();
				return repRes.getDimension(repId);
			}
			return super.getDimension(index, defValue);
		}
		
		@Override
		public int getDimensionPixelOffset(int index, int defValue) {
			Object replacement = getReplacement(getResourceId(index, 0));
			if (replacement instanceof XResForwarder) {
				Resources repRes = ((XResForwarder) replacement).getResources();
				int repId = ((XResForwarder) replacement).getId();
				return repRes.getDimensionPixelOffset(repId);
			}
			return super.getDimensionPixelOffset(index, defValue);
		}
		
		@Override
		public int getDimensionPixelSize(int index, int defValue) {
			Object replacement = getReplacement(getResourceId(index, 0));
			if (replacement instanceof XResForwarder) {
				Resources repRes = ((XResForwarder) replacement).getResources();
				int repId = ((XResForwarder) replacement).getId();
				return repRes.getDimensionPixelSize(repId);
			}
			return super.getDimensionPixelSize(index, defValue);
		}
		
		@Override
		public Drawable getDrawable(int index) {
			Object replacement = getReplacement(getResourceId(index, 0));
			if (replacement instanceof Drawable) {
				return (Drawable) replacement;
			} else if (replacement instanceof XResForwarder) {
				Resources repRes = ((XResForwarder) replacement).getResources();
				int repId = ((XResForwarder) replacement).getId();
				return repRes.getDrawable(repId);
			}
			return super.getDrawable(index);
		}
		
		@Override
		public float getFloat(int index, float defValue) {
			Object replacement = getReplacement(getResourceId(index, 0));
			if (replacement instanceof XResForwarder) {
				Resources repRes = ((XResForwarder) replacement).getResources();
				int repId = ((XResForwarder) replacement).getId();
				// dimensions seem to be the only way to define floats by references
				return repRes.getDimension(repId);
			}
			return super.getFloat(index, defValue);
		}
		
		@Override
		public float getFraction(int index, int base, int pbase, float defValue) {
			Object replacement = getReplacement(getResourceId(index, 0));
			if (replacement instanceof XResForwarder) {
				Resources repRes = ((XResForwarder) replacement).getResources();
				int repId = ((XResForwarder) replacement).getId();
				// dimensions seem to be the only way to define floats by references
				return repRes.getFraction(repId, base, pbase);
			}
			return super.getFraction(index, base, pbase, defValue);
		}
		
		@Override
		public int getInt(int index, int defValue) {
			Object replacement = getReplacement(getResourceId(index, 0));
			if (replacement instanceof Integer) {
				return (Integer) replacement;
			} else if (replacement instanceof XResForwarder) {
				Resources repRes = ((XResForwarder) replacement).getResources();
				int repId = ((XResForwarder) replacement).getId();
				return repRes.getInteger(repId);
			}
			return super.getInt(index, defValue);
		}
		
		@Override
		public int getInteger(int index, int defValue) {
			Object replacement = getReplacement(getResourceId(index, 0));
			if (replacement instanceof Integer) {
				return (Integer) replacement;
			} else if (replacement instanceof XResForwarder) {
				Resources repRes = ((XResForwarder) replacement).getResources();
				int repId = ((XResForwarder) replacement).getId();
				return repRes.getInteger(repId);
			}
			return super.getInteger(index, defValue);
		}
		
		@Override
		public String getString(int index) {
			Object replacement = getReplacement(getResourceId(index, 0));
			if (replacement instanceof CharSequence) {
				return replacement.toString();
			} else if (replacement instanceof XResForwarder) {
				Resources repRes = ((XResForwarder) replacement).getResources();
				int repId = ((XResForwarder) replacement).getId();
				return repRes.getString(repId);
			}
			return super.getString(index);
		}
		
		@Override
		public CharSequence getText(int index) {
			Object replacement = getReplacement(getResourceId(index, 0));
			if (replacement instanceof CharSequence) {
				return (CharSequence) replacement;
			} else if (replacement instanceof XResForwarder) {
				Resources repRes = ((XResForwarder) replacement).getResources();
				int repId = ((XResForwarder) replacement).getId();
				return repRes.getText(repId);
			}
			return super.getText(index);
		}
		
		@Override
		public CharSequence[] getTextArray(int index) {
			Object replacement = getReplacement(getResourceId(index, 0));
			if (replacement instanceof CharSequence[]) {
				return (CharSequence[]) replacement;
			} else if (replacement instanceof XResForwarder) {
				Resources repRes = ((XResForwarder) replacement).getResources();
				int repId = ((XResForwarder) replacement).getId();
				return repRes.getTextArray(repId);
			}
			return super.getTextArray(index);
		}
		
		// this is handled by XResources.loadXmlResourceParser:
		// public ColorStateList getColorStateList(int index);
	}
	
	// =======================================================
	//   INFLATING LAYOUTS
	// =======================================================
	
	private class XMLInstanceDetails {
		public final ResourceNames resNames;
		public final String variant;
		public final SortedSet<Callback> callbacks;
		public final XResources res = XResources.this;
		
		private XMLInstanceDetails(ResourceNames resNames, String variant, SortedSet<Callback> callbacks) {
			this.resNames = resNames;
			this.variant = variant;
			this.callbacks = callbacks;
		}
	}
	
	/** @see #hookLayout(String, String, String, Class, String, int) */
	public void hookLayout(int id, Class<?> handlerClass, String handlerMethodName, int priority) throws NoSuchMethodException {
		hookLayoutInternal(resDir, id, getResourceNames(id), handlerClass, handlerMethodName, priority);
	}
	
	/** @see #hookLayout(String, String, String, Class, String, int) */
	public void hookLayout(String fullName, Class<?> handlerClass, String handlerMethodName, int priority) throws NoSuchMethodException {
		int id = getIdentifier(fullName, null, null);
		if (id == 0)
			throw new NotFoundException(fullName);
		hookLayout(id, handlerClass, handlerMethodName, priority);
	}
	
	/**
	 * Add a function to be called once a specific layout has been inflated.
	 * <p>The handler method needs to have the signature described in {@link MethodSignatureGuide#handleLayoutInflated}.
	 * @param pkg Package, e.g. <code>com.android.systemui</code>
	 * @param type Type (in this case always <code>layout</code>)
	 * @param hookMethod The method to be hooked
	 * @param handlerClass Class of the handler
	 * @param handlerMethodName Method of the handler
	 * @param priority The higher the priority, the earlier this handler called.
	 * @throws NoSuchMethodException The handler method was not found
	 */
	public void hookLayout(String pkg, String type, String name, Class<?> handlerClass, String handlerMethodName, int priority) throws NoSuchMethodException {
		int id = getIdentifier(name, type, pkg);
		if (id == 0)
			throw new NotFoundException(pkg + ":" + type + "/" + name);
		hookLayout(id, handlerClass, handlerMethodName, priority);
	}
	
	/** @see #hookLayout(String, String, String, Class, String, int) */
	public static void hookSystemWideLayout(int id, Class<?> handlerClass, String handlerMethodName, int priority) throws NoSuchMethodException {
		if (id >= 0x7f000000)
			throw new IllegalArgumentException("ids >= 0x7f000000 are app specific and cannot be set for the framework");
		hookLayoutInternal(null, id, getSystemResourceNames(id), handlerClass, handlerMethodName, priority);
	}
	
	/** @see #hookLayout(String, String, String, Class, String, int) */
	public static void hookSystemWideLayout(String fullName, Class<?> handlerClass, String handlerMethodName, int priority) throws NoSuchMethodException {
		int id = getSystem().getIdentifier(fullName, null, null);
		if (id == 0)
			throw new NotFoundException(fullName);
		hookSystemWideLayout(id, handlerClass, handlerMethodName, priority);
	}
	
	/** @see #hookLayout(String, String, String, Class, String, int) */
	public static void hookSystemWideLayout(String pkg, String type, String name, Class<?> handlerClass, String handlerMethodName, int priority) throws NoSuchMethodException {
		int id = getSystem().getIdentifier(name, type, pkg);
		if (id == 0)
			throw new NotFoundException(pkg + ":" + type + "/" + name);
		hookSystemWideLayout(id, handlerClass, handlerMethodName, priority);
	}
	
	private synchronized static void hookLayoutInternal(String resDir, int id, ResourceNames resNames, Class<?> handlerClass, String handlerMethodName, int priority) throws NoSuchMethodException {
		if (id == 0)
			throw new IllegalArgumentException("id 0 is not an allowed resource identifier");

		Callback c = new Callback(handlerClass, handlerMethodName, priority, HOOK_LAYOUT_CALLBACK_PARAMS);
		XposedBridge.ensureMethodIsStatic(c.method);
		
		HashMap<String, SortedSet<Callback>> inner = layoutCallbacks.get(id);
		if (inner == null) {
			inner = new HashMap<String, SortedSet<Callback>>();
			layoutCallbacks.put(id, inner);
		}
		
		SortedSet<Callback>  callbacks = inner.get(resDir);
		if (callbacks == null) {
			callbacks = new TreeSet<Callback>();
			inner.put(resDir, callbacks);
		}
		
		c.method.setAccessible(true);
		callbacks.add(c);
		
		putResourceNames(resDir, resNames);
	}
	
	private static Object handleInflate(Iterator<Callback> iterator, Method method, Object thisObject, Object[] args) throws Throwable {
		Object result = XposedBridge.callNext(iterator, method, thisObject, args);
		try {
			XMLInstanceDetails details = xmlInstanceDetails.get(args[0]);
			if (details != null) {
				View view = (View) result;
				XposedBridge.callAll(details.callbacks, view, details.resNames, details.variant, details.res);
			}
		} catch (Throwable t) {
			XposedBridge.log(t);
		}
		return result;
	}
}