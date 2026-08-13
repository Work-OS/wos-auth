package com.gpr.auth.service;

import com.gpr.auth.dto.CompanyInfo;
import com.gpr.auth.dto.CreateCompanyRequest;
import com.gpr.auth.dto.IdentityCreateRequest;
import com.gpr.auth.entity.Company;
import com.gpr.auth.entity.User;
import com.gpr.auth.entity.UserCompany;
import com.gpr.auth.repository.CompanyRepository;
import com.gpr.auth.repository.UserCompanyRepository;
import com.gpr.auth.repository.UserRepository;
import com.gpr.kernel.dto.CompanyProfileDto;
import com.gpr.kernel.dto.UserSummaryDto;
import com.gpr.kernel.exception.ResourceNotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenancy authority: owns companies + membership. Super admins transcend membership (may act within
 * any company); everyone else is limited to the companies they belong to.
 */
@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final UserCompanyRepository userCompanyRepository;
    private final UserRepository userRepository;
    private final AuthService authService;

    /** The created company + the new Company Admin identity's userId (for WorkOS provisioning). */
    public record CompanyAdminResult(Long companyId, Long adminUserId, String name, String slug) {}

    /** Slugs the frontend reserves for its own routes (e.g. the company-less "/guest" space). */
    private static final java.util.Set<String> RESERVED_SLUGS = java.util.Set.of("guest", "api");

    @Transactional
    public CompanyAdminResult createCompany(CreateCompanyRequest req) {
        String slug = req.getSlug().trim();
        if (RESERVED_SLUGS.contains(slug.toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Company slug is reserved: " + slug);
        }
        if (companyRepository.existsBySlug(slug)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Company slug already in use: " + req.getSlug());
        }
        Company company = companyRepository.save(Company.builder()
                .name(req.getName().trim())
                .slug(req.getSlug().trim())
                .active(true)
                .build());

        IdentityCreateRequest idReq = new IdentityCreateRequest();
        idReq.setFirstName(req.getAdminFirstName());
        idReq.setLastName(req.getAdminLastName());
        idReq.setPassword(req.getAdminPassword());
        idReq.setEmail(req.getAdminEmail());
        idReq.setUsername(req.getAdminUsername());
        UserSummaryDto admin = authService.createIdentity(idReq, "workos");

        User adminUser = userRepository.findById(admin.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Admin identity vanished: " + admin.getId()));
        addMembership(adminUser, company);

        // Record the founding admin so each app can grant them its admin role on first provisioning.
        company.setAdminUserId(admin.getId());
        companyRepository.save(company);

        return new CompanyAdminResult(company.getId(), admin.getId(), company.getName(), company.getSlug());
    }

    /** The founding Company Admin's identity id, or null if not recorded — used by apps to grant their admin role. */
    @Transactional(readOnly = true)
    public Long getAdminUserId(Long companyId) {
        return findCompany(companyId).getAdminUserId();
    }

    @Transactional(readOnly = true)
    public List<CompanyInfo> companiesForUser(Long userId, boolean superAdmin) {
        if (superAdmin) {
            return companyRepository.findByActiveTrue().stream()
                    .map(this::toInfo)
                    .toList();
        }
        return userCompanyRepository.findByUserId(userId).stream()
                .filter(UserCompany::isActive)
                .map(UserCompany::getCompany)
                .filter(Company::isActive)
                .map(this::toInfo)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean canAccess(Long userId, Long companyId, boolean superAdmin) {
        if (superAdmin) {
            return companyRepository.findById(companyId).filter(Company::isActive).isPresent();
        }
        return userCompanyRepository.existsByUserIdAndCompanyId(userId, companyId);
    }

    public void addMembership(User user, Company company) {
        if (!userCompanyRepository.existsByUserIdAndCompanyId(user.getId(), company.getId())) {
            userCompanyRepository.save(UserCompany.builder()
                    .user(user)
                    .company(company)
                    .active(true)
                    .build());
        }
    }

    private CompanyInfo toInfo(Company c) {
        return new CompanyInfo(c.getId(), c.getName(), c.getSlug());
    }

    /** Resolves an active company by its public slug — used pre-login (e.g. branded login pages). */
    @Transactional(readOnly = true)
    public CompanyInfo findBySlug(String slug) {
        return companyRepository.findBySlug(slug.trim())
                .filter(Company::isActive)
                .map(this::toInfo)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found: " + slug));
    }

    /**
     * Resolves an active company by id. The profile endpoint deliberately carries only the editable
     * "My Company" fields and no name, so a caller holding an id (wos-payroll rendering a payslip
     * header) had no way to learn what the company is called.
     */
    @Transactional(readOnly = true)
    public CompanyInfo findById(Long id) {
        return companyRepository.findById(id)
                .filter(Company::isActive)
                .map(this::toInfo)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found: " + id));
    }

    // ── Company profile (the "My Company" details) ──────────────────────────────

    @Transactional(readOnly = true)
    public CompanyProfileDto getProfile(Long companyId) {
        return toProfile(findCompany(companyId));
    }

    @Transactional
    public CompanyProfileDto updateProfile(Long companyId, CompanyProfileDto dto) {
        Company c = findCompany(companyId);
        c.setTagline(dto.tagline());
        c.setAbout(dto.about());
        c.setIndustry(dto.industry());
        c.setFounded(dto.founded());
        c.setCompanySize(dto.companySize());
        c.setHeadquarters(dto.headquarters());
        c.setEmail(dto.email());
        c.setPhone(dto.phone());
        c.setWebsite(dto.website());
        c.setAddress(dto.address());
        return toProfile(companyRepository.save(c));
    }

    private Company findCompany(Long companyId) {
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found: " + companyId));
    }

    private CompanyProfileDto toProfile(Company c) {
        return new CompanyProfileDto(
                c.getTagline(), c.getAbout(), c.getIndustry(), c.getFounded(), c.getCompanySize(),
                c.getHeadquarters(), c.getEmail(), c.getPhone(), c.getWebsite(), c.getAddress());
    }
}
