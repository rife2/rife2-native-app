package workorders;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;

public class Photo {
    public static final String PHOTO = "photo";
    public static final String SIGNATURE = "signature";
    // only images that browsers show as images, an SVG or HTML "photo"
    // would run its scripts at the office
    public static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/heic", "image/heif", "image/webp");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    public static boolean isAccepted(String kind, String contentType) {
        if (SIGNATURE.equals(kind)) {
            return "image/png".equals(contentType);
        }
        return PHOTO.equals(kind) && contentType != null && TYPES.contains(contentType);
    }

    public static boolean isPng(byte[] data) {
        return data != null && data.length > PNG.length && Arrays.equals(data, 0, PNG.length, PNG, 0, PNG.length);
    }

    private Integer id_;
    private int orderId_;
    private String kind_ = PHOTO;
    private String contentType_;
    private byte[] data_;
    private Instant taken_;

    public Integer getId() { return id_; }
    public void setId(Integer id) { id_ = id; }
    public int getOrderId() { return orderId_; }
    public void setOrderId(int orderId) { orderId_ = orderId; }
    public String getKind() { return kind_; }
    public void setKind(String kind) { kind_ = kind; }
    public String getContentType() { return contentType_; }
    public void setContentType(String contentType) { contentType_ = contentType; }
    public byte[] getData() { return data_; }
    public void setData(byte[] data) { data_ = data; }
    public Instant getTaken() { return taken_; }
    public void setTaken(Instant taken) { taken_ = taken; }
}
