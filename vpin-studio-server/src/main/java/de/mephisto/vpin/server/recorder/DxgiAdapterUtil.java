package de.mephisto.vpin.server.recorder;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates DXGI adapters (GPUs) and their attached screens (Outputs).
 * This is used to resolve indices for FFmpeg's -init_hw_device d3d11va
 * [adapter_idx] and output_idx=[output_idx].
 */
public class DxgiAdapterUtil {

  private static final Logger LOG = LoggerFactory.getLogger(DxgiAdapterUtil.class);

  // IID_IDXGIFactory = {7b7166ec-21c7-44ae-b21a-c9ae321ae369}
  private static final byte[] IID_IDXGI_FACTORY = {
          (byte) 0xec, (byte) 0x66, (byte) 0x71, (byte) 0x7b,  // Data1 (little-endian)
          (byte) 0xc7, (byte) 0x21,                            // Data2 (little-endian)
          (byte) 0xae, (byte) 0x44,                            // Data3 (little-endian)
          (byte) 0xb2, (byte) 0x1a, (byte) 0xc9, (byte) 0xae,  // Data4
          (byte) 0x32, (byte) 0x1a, (byte) 0xe3, (byte) 0x69
  };

  /**
   * Represents a single physical screen and the exact DXGI indices
   * required to target it with FFmpeg ddagrab.
   */
  public static class ScreenInfo {
    public final int adapterIndex;
    public final int outputIndex;
    public final String deviceName;
    public final int left;
    public final int top;
    public final int right;
    public final int bottom;

    public ScreenInfo(int adapterIndex, int outputIndex, String deviceName,
                      int left, int top, int right, int bottom) {
      this.adapterIndex = adapterIndex;
      this.outputIndex = outputIndex;
      this.deviceName = deviceName;
      this.left = left;
      this.top = top;
      this.right = right;
      this.bottom = bottom;
    }
  }

  /**
   * Finds a screen based on its 'left' coordinate.
   * Returns the ScreenInfo object, or null if no matching screen is found.
   */
  public static ScreenInfo findScreenByLeft(int leftCoordinate) {
    List<ScreenInfo> allScreens = getAllScreensViaDxgi();

    for (ScreenInfo screen : allScreens) {
      if (screen.left == leftCoordinate) {
        return screen; // Found it!
      }
    }

    return null; // Not found
  }

  /**
   * Enumerates all screens across all GPUs using DXGI.
   * Returns a flat list containing the adapter index, output index, and screen bounds.
   */
  public static List<ScreenInfo> getAllScreensViaDxgi() {
    List<ScreenInfo> screens = new ArrayList<>();
    Pointer pFactory = null;

    try {
      NativeLibrary dxgi = NativeLibrary.getInstance("dxgi");
      Function createFactory = dxgi.getFunction("CreateDXGIFactory");

      Memory iid = new Memory(16);
      iid.write(0, IID_IDXGI_FACTORY, 0, 16);

      PointerByReference ppFactory = new PointerByReference();
      int hr = createFactory.invokeInt(new Object[]{iid, ppFactory});
      if (hr != 0) {
        LOG.warn("CreateDXGIFactory failed: HRESULT=0x{}", Integer.toHexString(hr));
        return screens;
      }
      pFactory = ppFactory.getValue();

      // Iterate Adapters (GPUs)
      for (int adapterIdx = 0; ; adapterIdx++) {
        PointerByReference ppAdapter = new PointerByReference();

        // Manually boxing adapterIdx to Object
        int enumAdapterHr = vtableCall(pFactory, 7, Integer.valueOf(adapterIdx), ppAdapter);
        if (enumAdapterHr != 0) break;

        Pointer pAdapter = ppAdapter.getValue();
        try {
          // Iterate Outputs (Screens)
          for (int outputIdx = 0; ; outputIdx++) {
            PointerByReference ppOutput = new PointerByReference();

            // Manually boxing outputIdx to Object
            int enumOutputHr = vtableCall(pAdapter, 7, Integer.valueOf(outputIdx), ppOutput);
            if (enumOutputHr != 0) break;

            Pointer pOutput = ppOutput.getValue();
            try {
              Memory desc = new Memory(128);
              desc.clear();
              // IDXGIOutput::GetDesc at vtable slot 7
              int descHr = vtableCall(pOutput, 7, desc);
              if (descHr == 0) {
                String deviceName = desc.getWideString(0);
                int left = desc.getInt(64);
                int top = desc.getInt(68);
                int right = desc.getInt(72);
                int bottom = desc.getInt(76);

                screens.add(new ScreenInfo(adapterIdx, outputIdx, deviceName,
                        left, top, right, bottom));
              }
            } finally {
              vtableCall(pOutput, 2); // Release Output
            }
          }
        } finally {
          vtableCall(pAdapter, 2); // Release Adapter
        }
      }
    } catch (Exception e) {
      LOG.error("DXGI screen enumeration failed: {}", e.getMessage(), e);
    } finally {
      if (pFactory != null) vtableCall(pFactory, 2); // Release Factory
    }
    return screens;
  }

  // --- JNA / COM Helpers ---

  private static int vtableCall(Pointer obj, int slot, Object... args) {
    Pointer vtable = obj.getPointer(0);
    Pointer fnPtr = vtable.getPointer((long) slot * Native.POINTER_SIZE);
    Object[] callArgs = new Object[args.length + 1];
    callArgs[0] = obj;
    System.arraycopy(args, 0, callArgs, 1, args.length);
    return Function.getFunction(fnPtr, Function.ALT_CONVENTION).invokeInt(callArgs);
  }
}