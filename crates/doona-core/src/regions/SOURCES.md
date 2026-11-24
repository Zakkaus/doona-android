# City locations

`cities.rs` contains display locations, not a live inventory of servers. Select the named metro of a public cloud region or major zone, not one city per availability zone. Keep separately named locations such as Tokyo and Saitama separate. District aliases such as Jurong West and Kwai Chung resolve to their city. Coordinates are approximate city centers in degrees, not facility addresses. A provider that publishes only a state or country does not establish a particular city.

Provider references for the 2026-11-24 table:

- AWS: [regions](https://aws.amazon.com/about-aws/global-infrastructure/regions_az/) and [Local Zones](https://aws.amazon.com/about-aws/global-infrastructure/localzones/locations/).
- [Google Compute Engine zones](https://cloud.google.com/compute/docs/regions-zones), [Azure physical locations](https://learn.microsoft.com/en-us/azure/reliability/regions-list), [Oracle Cloud regions and region keys](https://docs.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm).
- [Alibaba Cloud ECS regions](https://www.alibabacloud.com/help/en/ecs/user-guide/regions-and-zones), [Tencent Cloud CVM regions](https://www.tencentcloud.com/document/product/213/6091), [Huawei Cloud regions and endpoints](https://developer.huaweicloud.com/endpoint).
- [Vultr region API](https://api.vultr.com/v2/regions), [DigitalOcean datacenter codes](https://docs.digitalocean.com/platform/regional-availability/), [Akamai/Linode region API](https://api.linode.com/v4/regions), [Hetzner locations](https://docs.hetzner.com/cloud/general/locations/).
- [OVHcloud regions and Local Zones](https://www.ovhcloud.com/en/about-us/global-infrastructure/regions/), [Contabo locations](https://contabo.com/en/locations/), [Leaseweb data centers](https://www.leaseweb.com/en/platform/data-centers), [Equinix metros](https://www.equinix.com/data-centers).
- [PeeringDB facilities](https://www.peeringdb.com/api/fac?fields=city,country&depth=0): group facility records by country and city, not by network membership. Include the high-count metros and selected smaller interconnection hubs. Counts change as operators update their records.

Display names also serve as aliases. Keep ids stable when correcting a translation. `?` marks a word-like code that can stand alone in uppercase. `+` requires a number in the same token (`otp01`, `otp-01`, not `OTP 01`). `^` marks a mainland city abbreviation used at the start of a relay label. The markers are not part of a user's node name. Nothing fetches these sources at build time or runtime.
