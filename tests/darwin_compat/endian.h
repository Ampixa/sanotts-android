/* macOS has no <endian.h>; espeak-ng wants BYTE_ORDER and the le*toh family
 * from it. Only on the include path for host builds on Darwin (see
 * host_test.py); every Mac this runs on is little-endian. */
#include <machine/endian.h>
#define le16toh(x) (x)
#define le32toh(x) (x)
#define htole16(x) (x)
#define htole32(x) (x)
