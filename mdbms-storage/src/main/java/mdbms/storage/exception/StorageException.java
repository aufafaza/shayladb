package mdbms.storage.exception;

/** Kesalahan penyimpanan dengan kode terstruktur dan penyebab asli bila tersedia. */
public class StorageException extends RuntimeException {
    private final StorageErrorCode code;

    /** Membuat kesalahan yang tidak membungkus exception lain, misalnya input blok tidak valid. */
    public StorageException(StorageErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    /** Mempertahankan exception sumber agar kegagalan file atau FRM dapat ditelusuri. */
    public StorageException(StorageErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** Mengembalikan kategori kesalahan untuk penanganan oleh pemanggil. */
    public StorageErrorCode getCode() {
        return code;
    }
}
