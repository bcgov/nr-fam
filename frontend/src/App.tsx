import { Theme } from "@carbon/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { FC, ReactNode } from "react";
import { BrowserRouter, Navigate, Outlet, Route, Routes } from "react-router-dom";
import { Layout } from "@/components/Layout";
import { SessionTimeout } from "@/components/SessionTimeout";
import { SelectedAppProvider } from "@/context/application/SelectedAppProvider";
import { AuthProvider } from "@/context/auth/AuthProvider";
import { useAuth } from "@/context/auth/useAuth";
import { NotificationProvider } from "@/context/notification/NotificationProvider";
import { Landing } from "@/pages/Landing";
import { AddAppPermission } from "@/pages/AddAppPermission";
import { EditAppPermission } from "@/pages/EditAppPermission";
import { AddApplicationAdmin } from "@/pages/AddApplicationAdmin";
import { AddDevopsAdmin } from "@/pages/AddDevopsAdmin";
import { AddDelegatedAdmin } from "@/pages/AddDelegatedAdmin";
import { BulkGrant } from "@/pages/BulkGrant";
import { ManagePermissions } from "@/pages/ManagePermissions";
import { ManageRoles } from "@/pages/ManageRoles";
import { MyPermissions } from "@/pages/MyPermissions";
import { UserPermissionHistory } from "@/pages/UserPermissionHistory";
import { UserHistory } from "@/pages/UserHistory";
import { RequireGrantTarget } from "@/pages/RequireGrantTarget";
import { NoAccess } from "@/pages/NoAccess";
import { RequireAuth, RequireFamAdmin, RequireRoleManager } from "@/routes/guards";
import { RedirectIfSignedIn } from "@/routes/guards";
import { homeRouteFor, ROUTES } from "@/routes/routePaths";

/**
 * Carried over from the Vue app unchanged.
 *
 * `refetchOnMount: false` with a long staleTime is why individual queries opt
 * back in - and why invalidation has to mark things stale explicitly. See
 * utils/QueryInvalidation.
 */
const THREE_HOURS = 3 * 60 * 60 * 1000;

const queryClient = new QueryClient({
    defaultOptions: {
        queries: {
            refetchOnMount: false,
            refetchOnWindowFocus: false,
            staleTime: THREE_HOURS,
            gcTime: THREE_HOURS,
        },
    },
});

/**
 * The signed-in shell: everything below it is behind the session.
 *
 * A layout route rather than a wrapper around `<Routes>` so the landing and
 * no-access pages render without the header and drawer - both are for people
 * who have nowhere to navigate to, and a nav offering pages they cannot open
 * would only mislead.
 */
/**
 * Renders its children only while somebody is signed in.
 *
 * The idle guard needs this: mounted unconditionally it would start counting
 * down against the sign-in screen, and mounted inside the routes it would miss
 * /no-access, which has a live session and so a session that can go stale.
 */
const SignedInOnly: FC<{ children: ReactNode }> = ({ children }) => {
    const { authState } = useAuth();
    return authState.isAuthenticated ? <>{children}</> : null;
};

/**
 * Wherever this person starts.
 *
 * <p>A component rather than a constant because the answer depends on the roles
 * on the token - see {@code homeRouteFor}.
 */
const HomeRedirect: FC = () => {
    const { authState } = useAuth();
    return <Navigate to={homeRouteFor(authState.accessRoles)} replace />;
};

const ProtectedLayout: FC = () => {
    const { authState } = useAuth();

    return (
        <RequireAuth>
            <Layout accessRoles={authState.accessRoles}>
                <div id="protected-layout-container">
                    <Outlet />
                </div>
            </Layout>
        </RequireAuth>
    );
};

/**
 * The React application, mid-migration.
 *
 * Every route is declared from the start so the shell and the nav are honest
 * about what exists; the ones still on Vue render a placeholder saying so
 * rather than 404ing, which would be indistinguishable from a routing mistake.
 */
export const App: FC = () => (
    <QueryClientProvider client={queryClient}>
        <BrowserRouter>
            {/*
                AuthProvider sits inside the router because it navigates: it
                handles the OIDC redirect callback itself and sends the user on
                to Manage permissions, so it needs useNavigate.
            */}
            <AuthProvider>
                <NotificationProvider>
                <SelectedAppProvider>
                {/*
                    Carbon defines its colour tokens under a theme selector -
                    `.cds--white` and `:root[data-carbon-theme=white]` - never on
                    a bare `:root`. Without this the tokens resolve to nothing:
                    `--cds-background-brand` was empty, so the header painted
                    transparent and its white title vanished against the page.

                    Fixed to white rather than the light/dark toggle nr-fsp-new
                    carries. FAM has never supported dark mode, and a toggle that
                    only ever has one position is machinery with no purpose.
                */}
                <Theme theme="white">
                    {/*
                        The inactivity guard, inside the providers it needs and
                        outside the routes so it covers every signed-in screen -
                        including /no-access, which is behind a session too.
                        It renders nothing until it has something to say.
                    */}
                    <SignedInOnly>
                        <SessionTimeout />
                    </SignedInOnly>
                    <Routes>
                        <Route
                            path={ROUTES.landing}
                            element={
                                <RedirectIfSignedIn>
                                    <Landing />
                                </RedirectIfSignedIn>
                            }
                        />
                        {/*
                            Outside the shell but still behind a session: it is
                            reached by signing in successfully and holding no
                            FAM role, and its only action is to sign out again.
                        */}
                        <Route
                            path={ROUTES.noAccess}
                            element={
                                <RequireAuth>
                                    <NoAccess />
                                </RequireAuth>
                            }
                        />

                        <Route element={<ProtectedLayout />}>
                            <Route
                                path={ROUTES.managePermissions}
                                element={<ManagePermissions />}
                            />
                            {/*
                                Every grant screen needs an application, which
                                arrives on the query string. RequireGrantTarget
                                turns a URL without one into a trip back to
                                Manage permissions rather than a form that
                                cannot load.
                            */}
                            <Route
                                path={ROUTES.addAppPermission}
                                element={
                                    <RequireGrantTarget>
                                        <AddAppPermission />
                                    </RequireGrantTarget>
                                }
                            />
                            {/*
                                Behind the same guard as granting: editing what
                                somebody holds is the same authority as giving it
                                to them, and it needs the same application in
                                context to know what it is editing.
                            */}
                            <Route
                                path={ROUTES.editAppPermission}
                                element={
                                    <RequireGrantTarget>
                                        <EditAppPermission />
                                    </RequireGrantTarget>
                                }
                            />
                            <Route
                                path={ROUTES.addDelegatedAdmin}
                                element={
                                    <RequireGrantTarget>
                                        <AddDelegatedAdmin />
                                    </RequireGrantTarget>
                                }
                            />
                            <Route
                                path={ROUTES.addApplicationAdmin}
                                element={
                                    <RequireGrantTarget>
                                        <AddApplicationAdmin />
                                    </RequireGrantTarget>
                                }
                            />
                            {/*
                                FAM administrators only, matching the tab that
                                leads here: the tier below cannot hand out
                                authority it does not hold.
                            */}
                            <Route
                                path={ROUTES.addDevopsAdmin}
                                element={
                                    <RequireFamAdmin>
                                        <RequireGrantTarget>
                                            <AddDevopsAdmin />
                                        </RequireGrantTarget>
                                    </RequireFamAdmin>
                                }
                            />
                            <Route
                                path={ROUTES.bulkGrant}
                                element={
                                    <RequireGrantTarget>
                                        <BulkGrant />
                                    </RequireGrantTarget>
                                }
                            />
                            {/*
                                Not FAM administrators alone any more: a DevOps
                                administrator manages the roles of the
                                applications they were appointed for, and the
                                picker on the screen offers only those.
                            */}
                            <Route
                                path={ROUTES.manageRoles}
                                element={
                                    <RequireRoleManager>
                                        <ManageRoles />
                                    </RequireRoleManager>
                                }
                            />
                            <Route
                                path={ROUTES.myPermissions}
                                element={<MyPermissions />}
                            />
                            <Route
                                path={ROUTES.permissionHistory}
                                element={<UserPermissionHistory />}
                            />
                            {/*
                                Any tier, for the applications they administer -
                                the endpoint checks the one they name, so there
                                is nothing for a route guard to add beyond being
                                signed in.
                            */}
                            <Route
                                path={ROUTES.userHistory}
                                element={<UserHistory />}
                            />
            {/*
                                A mistyped URL lands somewhere real rather than on
                                a blank screen - the same screen signing in would
                                have taken them to, which depends on what they
                                administer.
                            */}
                            <Route path="*" element={<HomeRedirect />} />
                        </Route>
                    </Routes>
                </Theme>
                </SelectedAppProvider>
                </NotificationProvider>
            </AuthProvider>
        </BrowserRouter>
    </QueryClientProvider>
);

export default App;
